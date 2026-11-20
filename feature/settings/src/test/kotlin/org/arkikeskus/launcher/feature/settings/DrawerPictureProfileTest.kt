package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
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

    /** Settings opens where it was left: each test starts on the main page. */
    @Before fun nothingRemembered() = SettingsScrollMemory.clear()

    private val recorded = RecordedActions()
    private val picture = DrawerWallpaper(File.createTempFile("wallpaper", ".jpg").apply { deleteOnExit() }, luminance = 0.3f)

    /**
     * Review round 4. The picker is opened on the page of one profile; when it answers, the page can show
     * another (a switch made meanwhile). The picture is for the profile the picker was OPENED for: handed the
     * profile of the answer's moment, it replaced the other profile's picture and left the first as it was.
     */
    @Test fun aPickIsForTheProfileThePickerWasOpenedForThoughThePageShowsAnotherWhenItAnswers() {
        val launched = mutableListOf<Int>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launched += code
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        var settings by mutableStateOf(LauncherSettings(homeProfileId = 2))
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                SettingsContent(s = settings, lists = SettingsLists(), actions = recorded.actions, blurAvailability = BlurAvailability.AVAILABLE)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(categoryTag(SettingsCategory.DRAWER.key)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DRAWER_WALLPAPER_PICK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(launched).hasSize(1)

        // The profile is switched while the picker is open: the page is the first profile's now.
        settings = LauncherSettings(homeProfileId = 1)
        compose.waitForIdle()
        val picked = Uri.parse("content://media/picker/0/picture/1")
        compose.runOnUiThread { registry.dispatchResult(launched.single(), picked) }
        compose.waitForIdle()

        assertThat(recorded.calls).contains("importDrawerWallpaper" to listOf(picked, 2L))
    }

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
