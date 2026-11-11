package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The narrowest case: a 320 dp screen, the largest font size and the longest languages. The category rows and the
 * page headers wrap their text without cutting any of it, and what is tapped stays at least 48 dp high.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, so a long translation really is wide
class SettingsNarrowTest {
    @get:Rule val compose = createComposeRule()

    @Before fun nothingRemembered() = SettingsScrollMemory.clear()
    private val res get() = RuntimeEnvironment.getApplication().resources

    private fun show() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    SettingsContent(
                        s = LauncherSettings(),
                        lists = SettingsLists(),
                        actions = RecordedActions().actions,
                        blurAvailability = BlurAvailability.AVAILABLE,
                        backupScreen = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private val hasTextLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

    /** Every text under [node] is laid out whole: nothing clipped, nothing ending in an ellipsis. */
    private fun assertNoTextIsCut(node: SemanticsNode, where: String) {
        val texts = buildList {
            fun walk(n: SemanticsNode) {
                if (n.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) add(n)
                n.children.forEach(::walk)
            }
            walk(node)
        }
        assertWithMessage("$where has text").that(texts).isNotEmpty()
        for (text in texts) {
            val results = mutableListOf<TextLayoutResult>()
            text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.first()
            val shown = text.config[SemanticsProperties.Text].joinToString { it.text }
            // Held line by line: the layout's own overflow flag also answers true for a text that merely wraps its
            // content narrower than the room it was offered.
            val room = layout.layoutInput.constraints.maxWidth
            for (line in 0 until layout.lineCount) {
                assertWithMessage("$where: \"$shown\" line $line ends in an ellipsis").that(layout.isLineEllipsized(line)).isFalse()
                assertWithMessage("$where: \"$shown\" line $line is wider than its room")
                    .that(layout.getLineRight(line) - layout.getLineLeft(line)).isAtMost(room + 0.5f)
            }
            assertWithMessage("$where: \"$shown\" is cut at the bottom").that(layout.didOverflowHeight).isFalse()
            assertWithMessage("$where: \"$shown\" is laid out to its end")
                .that(layout.getLineEnd(layout.lineCount - 1)).isEqualTo(shown.length)
        }
    }

    private fun theMainPageAndEveryPageHeaderFit() {
        show()
        for (category in SettingsCategory.entries) {
            val row = compose.onNodeWithTag(categoryTag(category.key), useUnmergedTree = true)
            row.performScrollTo()
            assertNoTextIsCut(row.fetchSemanticsNode(), "category ${category.key}")
            compose.onNodeWithTag(categoryTag(category.key)).assertHeightIsAtLeast(48.dp)
        }
        for (category in SettingsCategory.entries) {
            if (category == SettingsCategory.BACKUP) continue
            compose.onNodeWithTag(categoryTag(category.key)).performScrollTo().performClick()
            compose.waitForIdle()
            val header = compose.onNodeWithTag(PAGE_HEADER_TAG, useUnmergedTree = true)
            assertNoTextIsCut(header.fetchSemanticsNode(), "header ${category.key}")
            val back = compose.onNodeWithContentDescription(res.getString(R.string.settings_back))
            // The arrow is drawn in a 40 dp button; what counts is the area that takes the touch.
            val touch = back.fetchSemanticsNode().touchBoundsInRoot
            val least = with(compose.density) { 48.dp.toPx() } - 0.5f
            assertWithMessage("the back arrow's touch height").that(touch.height).isAtLeast(least)
            assertWithMessage("the back arrow's touch width").that(touch.width).isAtLeast(least)
            back.performClick()
            compose.waitForIdle()
        }
    }

    @Test
    @Config(sdk = [35], application = Application::class, qualifiers = "de")
    fun inGerman() = theMainPageAndEveryPageHeaderFit()

    @Test
    @Config(sdk = [35], application = Application::class, qualifiers = "ru")
    fun inRussian() = theMainPageAndEveryPageHeaderFit()

    @Test
    @Config(sdk = [35], application = Application::class, qualifiers = "fi")
    fun inFinnish() = theMainPageAndEveryPageHeaderFit()
}
