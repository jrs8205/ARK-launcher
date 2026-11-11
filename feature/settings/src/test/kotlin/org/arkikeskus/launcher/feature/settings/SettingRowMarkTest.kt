package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.READABLE_TEXT
import org.arkikeskus.launcher.ui.contrastRatio
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * The mark on a row a search result led to must not cost the row its legibility: it is drawn around the row's card,
 * never over what is written on it, so the card stays the card's colour under the text while the mark is at its
 * strongest, in both themes. And it is a passing thing: whatever becomes of the scroll that brings the row into view,
 * and whichever row is asked for next, no row is left wearing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingRowMarkTest {
    @get:Rule val compose = createComposeRule()

    private val light = settingsPalette(dark = false)
    private val res = RuntimeEnvironment.getApplication().resources

    private fun near(a: Color, b: Color) =
        abs(a.red - b.red) < 0.02f && abs(a.green - b.green) < 0.02f && abs(a.blue - b.blue) < 0.02f

    private fun theMarkLeavesTheCardUnderTheTextAlone(dark: Boolean) {
        val palette = settingsPalette(dark)
        val jumps = SettingJumps().apply { target = "G4" }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides palette, LocalSettingJumps provides jumps) {
                Column(Modifier.width(320.dp)) {
                    SettingRow("G4") { ExpressiveActionRow(label = "Left edge", description = "None") {} }
                }
            }
        }
        // Inside the mark's hold: it is at its strongest.
        compose.mainClock.advanceTimeBy(120)
        assertThat(jumps.target).isEqualTo("G4")

        val pixels = compose.onNodeWithTag(settingTag("G4")).captureToImage().toPixelMap()
        val middle = (pixels.height - 10) / 2 // the frame's last 10 px are the gap under the card
        // Well inside the card, left of where the text starts (the card pads its content by 20).
        val inside = pixels[10, middle]
        assertWithMessage("the card under the text keeps its colour (was $inside)").that(near(inside, palette.surfaceHi)).isTrue()
        // So what is written on the row reads as it does without the mark.
        assertThat(contrastRatio(palette.accent.luminance(), inside.luminance())).isAtLeast(READABLE_TEXT)
        assertThat(contrastRatio(palette.dim.luminance(), inside.luminance())).isAtLeast(READABLE_TEXT)
        // And the mark is there: the card's edge wears it.
        val edge = pixels[1, middle]
        assertWithMessage("the card's edge wears the mark (was $edge)").that(near(edge, palette.surfaceHi)).isFalse()
        assertThat(contrastRatio(edge.luminance(), palette.bg.luminance())).isAtLeast(3f)
    }

    /** A few rows on the light theme, with [jumps] to steer them, inside [container]. */
    private fun showRows(jumps: SettingJumps, count: Int, container: Modifier = Modifier) {
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides light, LocalSettingJumps provides jumps) {
                Column(Modifier.width(320.dp).then(container)) {
                    for (n in 1..count) SettingRow("R$n") { ExpressiveActionRow(label = "Row $n", description = "None") {} }
                }
            }
        }
    }

    /** Whether the row wears the mark right now: its card's edge is not the card's colour. */
    private fun wearsTheMark(id: String): Boolean {
        val pixels = compose.onNodeWithTag(settingTag(id)).captureToImage().toPixelMap()
        return !near(pixels[1, (pixels.height - 10) / 2], light.surfaceHi)
    }

    private fun holdsTheFocus(id: String): Boolean =
        compose.onNodeWithTag(settingTag(id)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    @Test fun aFingerOnThePageDuringTheScrollDoesNotLeaveTheMarkOn() {
        SettingsScrollMemory.clear()
        compose.setContent {
            SettingsContent(
                s = LauncherSettings(),
                lists = SettingsLists(),
                actions = RecordedActions().actions,
                blurAvailability = BlurAvailability.AVAILABLE,
                backupScreen = {},
            )
        }
        compose.waitForIdle()
        // The last row of the longest page: the page has a long way to scroll to it.
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(res.getString(R.string.settings_mix_drawer_folders))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(searchHitTag("A21")).performScrollTo().performClick()

        val row = compose.onNodeWithTag(settingTag("A21"))
        val screen = compose.onRoot().getUnclippedBoundsInRoot().bottom
        fun rowTop() = runCatching { row.getUnclippedBoundsInRoot().top }.getOrNull()
        // Frame by frame until the row has come onto the screen, the page still on its way to it.
        var frames = 0
        while ((rowTop() ?: screen) > screen - 40.dp && frames++ < 200) compose.mainClock.advanceTimeByFrame()
        assertWithMessage("the page is still on its way").that(row.getUnclippedBoundsInRoot().bottom).isGreaterThan(screen)

        // A finger takes the page over, moves it a little and rests on it.
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, -20f))
            advanceEventTime(100)
            up()
        }
        val left = row.getUnclippedBoundsInRoot()
        row.assertIsDisplayed()
        assertWithMessage("the row is marked when the finger lifts").that(wearsTheMark("A21")).isTrue()

        // Well past the mark's hold and fade.
        compose.mainClock.advanceTimeBy(4_000)
        // The page stayed where the finger left it: the scroll it took over did not go on to bring the row all the way in.
        assertThat(row.getUnclippedBoundsInRoot().top).isEqualTo(left.top)
        assertThat(left.bottom).isGreaterThan(screen)
        row.assertIsDisplayed()
        assertWithMessage("the row still wears the mark").that(wearsTheMark("A21")).isFalse()
        assertWithMessage("the row still holds the focus").that(holdsTheFocus("A21")).isFalse()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aScrollThatIsCancelledStillLetsTheMarkFade() {
        val jumps = SettingJumps().apply { target = "R1" }
        compose.mainClock.autoAdvance = false
        // The rows sit in a container that cancels every request to bring one of them into view.
        showRows(jumps, count = 1, container = CancelsBringIntoView)
        compose.mainClock.advanceTimeBy(120)
        assertThat(wearsTheMark("R1")).isTrue()
        // Well past the mark's hold and fade.
        compose.mainClock.advanceTimeBy(4_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertWithMessage("the row still wears the mark").that(wearsTheMark("R1")).isFalse()
        assertThat(jumps.target).isNull()
    }

    @Test fun aNewerJumpTakesTheMarkOffTheEarlierRow() {
        val jumps = SettingJumps().apply { target = "R1" }
        compose.mainClock.autoAdvance = false
        showRows(jumps, count = 3)
        // The first row's mark has begun to fade when another row is asked for.
        compose.mainClock.advanceTimeBy(400)
        assertThat(wearsTheMark("R1")).isTrue()
        compose.runOnUiThread { Snapshot.withMutableSnapshot { jumps.target = "R2" } }
        compose.mainClock.advanceTimeBy(120)
        assertWithMessage("the later row is marked").that(wearsTheMark("R2")).isTrue()
        assertWithMessage("the earlier row still wears the mark").that(wearsTheMark("R1")).isFalse()
        compose.mainClock.advanceTimeBy(4_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertWithMessage("the later row's mark has faded").that(wearsTheMark("R2")).isFalse()
        assertThat(jumps.target).isNull()
    }

    @Test fun onTheLightTheme() = theMarkLeavesTheCardUnderTheTextAlone(dark = false)

    @Test fun onTheDarkTheme() = theMarkLeavesTheCardUnderTheTextAlone(dark = true)
}

/** A container that answers every request to bring a child into view by cancelling it, as a scroll taken over would. */
private object CancelsBringIntoView : ModifierNodeElement<CancellingNode>() {
    override fun create() = CancellingNode()
    override fun update(node: CancellingNode) = Unit
    override fun hashCode() = 1
    override fun equals(other: Any?) = other === this
}

private class CancellingNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        throw CancellationException("the scroll was taken over")
    }
}
