package org.arkikeskus.launcher.ui

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.AppIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.roundToInt

/**
 * An app opens with a zoom from the icon it was tapped on, as in Pixel Launcher: the icon the finger
 * landed on hands its place on screen to the launch. A launch that did not come from a touch on that
 * app's icon (TalkBack, a stale touch) opens without it rather than from a wrong place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppLaunchSourcesTest {
    @get:Rule val compose = createComposeRule()

    private val sources = AppLaunchSources()
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLaunchSources provides sources) {
                Column(Modifier.padding(top = 100.dp, start = 40.dp)) {
                    AppIcon(appItem = mail, labelColor = Color.White, iconSize = 56.dp)
                    AppIcon(appItem = maps, labelColor = Color.White, iconSize = 56.dp)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun iconBounds(app: AppItem) =
        compose.onNodeWithContentDescription(app.label).fetchSemanticsNode().boundsInRoot

    @Test fun aTappedIconHandsItsPlaceToTheLaunch() {
        show()
        val icon = iconBounds(mail)

        compose.onNodeWithText("Mail").performClick()
        val launch = sources.take(mail)

        assertThat(launch).isNotNull()
        val bounds = launch!!.sourceBounds
        assertThat(bounds.left).isEqualTo(icon.left.roundToInt())
        assertThat(bounds.top).isEqualTo(icon.top.roundToInt())
        assertThat(bounds.width()).isEqualTo(icon.width.roundToInt())
        assertThat(bounds.height()).isEqualTo(icon.height.roundToInt())
        // The window scales up from the icon (ActivityOptions.ANIM_SCALE_UP).
        assertThat(launch.options.getInt("android:activity.animType")).isEqualTo(2)
        assertThat(launch.options.getInt("android:activity.animWidth")).isEqualTo(icon.width.roundToInt())
    }

    @Test fun aLaunchOfAnotherAppGetsNoZoom() {
        show()

        compose.onNodeWithText("Mail").performClick()

        assertThat(sources.take(maps)).isNull()
    }

    @Test fun eachTouchServesOneLaunch() {
        show()

        compose.onNodeWithText("Mail").performClick()

        assertThat(sources.take(mail)).isNotNull()
        assertThat(sources.take(mail)).isNull()
    }

    /** TalkBack activates an icon without touching it: no zoom then, and none from an older touch. */
    @Test fun aLaunchWithoutATouchGetsNoZoom() {
        show()

        assertThat(sources.take(mail)).isNull()
    }

    @Test fun aStaleTouchGetsNoZoom() {
        show()

        compose.onNodeWithText("Mail").performClick()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))

        assertThat(sources.take(mail)).isNull()
    }

    /** Two fingers on two icons of one app: the finger that lifts — the tap that launches — decides. */
    @Test fun theLiftingFingersIconDecidesBetweenTwoIconsOfOneApp() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLaunchSources provides sources) {
                Column(Modifier.padding(top = 100.dp, start = 40.dp)) {
                    AppIcon(appItem = mail, labelColor = Color.White, iconSize = 56.dp)
                    AppIcon(appItem = mail, labelColor = Color.White, iconSize = 56.dp)
                }
            }
        }
        compose.waitForIdle()
        val icons = compose.onAllNodesWithContentDescription("Mail").fetchSemanticsNodes().map { it.boundsInRoot }

        compose.onRoot().performTouchInput {
            down(0, icons[0].center)
            down(1, icons[1].center)
            up(0)
        }
        val launch = sources.take(mail)

        assertThat(launch!!.sourceBounds.top).isEqualTo(icons[0].top.roundToInt())
    }

    /** A touch that never lifts on the icon (the gesture was cancelled) leaves nothing for a later launch. */
    @Test fun aCancelledTouchLeavesNothing() {
        show()

        compose.onNodeWithText("Mail").performTouchInput {
            down(center)
            cancel()
        }

        assertThat(sources.take(mail)).isNull()
    }

    @Test fun anIconThatLeftGivesNoZoom() {
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalAppLaunchSources provides sources) {
                if (shown) AppIcon(appItem = mail, labelColor = Color.White, iconSize = 56.dp)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Mail").performClick()
        shown = false
        compose.waitForIdle()

        assertThat(sources.take(mail)).isNull()
    }

    /** A home or dock cell is larger than its icon and the whole cell takes the tap: a tap at its edge
     *  zooms from the icon too. */
    @Test fun aTapAtACellsEdgeZoomsFromItsIcon() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLaunchSources provides sources) {
                val origin = rememberAppLaunchOrigin()
                Box(
                    Modifier.padding(top = 100.dp, start = 40.dp).size(140.dp).appLaunchTouch(mail, origin).testTag("cell"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(appItem = mail, labelColor = Color.White, iconSize = 48.dp, launchOrigin = origin)
                }
            }
        }
        compose.waitForIdle()
        val icon = iconBounds(mail)

        compose.onNodeWithTag("cell").performTouchInput { click(Offset(4f, 4f)) }
        val launch = sources.take(mail)

        assertThat(launch).isNotNull()
        assertThat(launch!!.sourceBounds.left).isEqualTo(icon.left.roundToInt())
        assertThat(launch.sourceBounds.width()).isEqualTo(icon.width.roundToInt())
    }

    @Test fun withoutTheLauncherRootNothingIsRecorded() {
        compose.setContent { AppIcon(appItem = mail, labelColor = Color.White) }
        compose.waitForIdle()

        compose.onNodeWithText("Mail").performClick()

        assertThat(sources.take(mail)).isNull()
    }
}
