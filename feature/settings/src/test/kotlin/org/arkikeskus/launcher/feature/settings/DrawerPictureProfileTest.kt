package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The picture behind the app drawer is one per home profile, and the page is about one profile: what it asks
 * for names that profile. The store behind the action follows a profile switch a moment late, so "the profile
 * the store shows" is not the one the user is looking at right after a switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerPictureProfileTest {
    @get:Rule val compose = createComposeRule()

    private val recorded = RecordedActions()
    private val picture = DrawerWallpaper(File.createTempFile("wallpaper", ".jpg").apply { deleteOnExit() }, luminance = 0.3f)

    @Test fun aRemovalNamesTheProfileThePageShows() {
        compose.setContent {
            SettingsContent(
                s = LauncherSettings(homeProfileId = 2),
                lists = SettingsLists(drawerWallpaper = picture),
                actions = recorded.actions,
                blurAvailability = BlurAvailability.AVAILABLE,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(categoryTag(SettingsCategory.DRAWER.key)).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(DRAWER_WALLPAPER_REMOVE_TAG).performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(recorded.calls).contains("clearDrawerWallpaper" to listOf(2L))
    }
}
