package org.arkikeskus.launcher.ui

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    @Test fun withoutTheLauncherRootNothingIsRecorded() {
        compose.setContent { AppIcon(appItem = mail, labelColor = Color.White) }
        compose.waitForIdle()

        compose.onNodeWithText("Mail").performClick()

        assertThat(sources.take(mail)).isNull()
    }
}
