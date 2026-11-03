package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The drawer wallpaper row (a Telegram request after 0.9.9): without a picture it says so and offers a
 * pick; with one it shows a preview and a remove button that reports the removal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerWallpaperRowTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private var cleared = 0

    private fun show(wallpaper: DrawerWallpaper?) {
        compose.setContent { DrawerWallpaperRow(wallpaper = wallpaper, onPick = {}, onClear = { cleared++ }) }
        compose.waitForIdle()
    }

    @Test fun withoutAPictureTheRowSaysSoAndOffersAPick() {
        show(null)

        compose.onNodeWithText(res.getString(R.string.settings_drawer_wallpaper)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_drawer_wallpaper_none)).assertIsDisplayed()
        compose.onNodeWithTag(DRAWER_WALLPAPER_PICK_TAG).assertIsDisplayed()
        compose.onNodeWithTag(DRAWER_WALLPAPER_REMOVE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DRAWER_WALLPAPER_PREVIEW_TAG).assertDoesNotExist()
    }

    @Test fun withAPictureTheRowShowsItAndRemovesItOnRequest() {
        val file = File.createTempFile("wallpaper", ".jpg").apply { deleteOnExit() }
        show(DrawerWallpaper(file, luminance = 0.3f))

        compose.onNodeWithTag(DRAWER_WALLPAPER_PREVIEW_TAG).assertExists()
        compose.onNodeWithText(res.getString(R.string.settings_drawer_wallpaper_none)).assertDoesNotExist()
        compose.onNodeWithTag(DRAWER_WALLPAPER_PICK_TAG).assertIsDisplayed()
        compose.onNodeWithTag(DRAWER_WALLPAPER_REMOVE_TAG).assertIsDisplayed().performClick()

        assertThat(cleared).isEqualTo(1)
    }
}
