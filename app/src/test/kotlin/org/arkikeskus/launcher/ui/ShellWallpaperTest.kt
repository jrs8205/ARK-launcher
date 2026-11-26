package org.arkikeskus.launcher.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.LauncherActivity
import org.arkikeskus.launcher.LauncherApplication
import org.arkikeskus.launcher.feature.home.HomeUiState
import org.arkikeskus.launcher.feature.home.PlacedFolder
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.WallpaperLook
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LauncherApplication::class, shadows = [ShellWallpaperTest.Colours::class])
class ShellWallpaperTest {
    @get:Rule val compose = createAndroidComposeRule<LauncherActivity>()

    @Implements(WallpaperManager::class)
    class Colours {
        @Implementation fun getWallpaperColors(which: Int): WallpaperColors? = null
        @Implementation fun addOnColorsChangedListener(callback: WallpaperManager.OnColorsChangedListener, handler: Handler?) {
            listeners += callback
        }
        @Implementation fun removeOnColorsChangedListener(callback: WallpaperManager.OnColorsChangedListener) {
            listeners -= callback
        }
        companion object {
            val listeners = mutableSetOf<WallpaperManager.OnColorsChangedListener>()
        }
    }

    private val folder = PlacedFolder(12, "Wallpaper check", emptyList(), 0, 0, 0)
    private val primary = 0xfffafafa.toInt()

    private fun showHome() {
        val settings = LauncherSettings(automaticHomeLabels = true, defaultReminderDismissed = true)
        val frame = HomeUiState(settings = settings, loaded = true, homeProfileId = 1, entries = listOf(folder))
        compose.runOnUiThread {
            // A constant profile frame lets only the shell's live wallpaper report recolour the folder.
            compose.activity.setContent {
                LauncherShell(frame, settings, onManageProfiles = {}, homeSignals = emptyFlow(), onOpenSettings = {})
            }
        }
        compose.onNodeWithText(folder.name, useUnmergedTree = true).assertExists()
    }

    private fun announceBrightWallpaper() = compose.runOnIdle {
        val report = WallpaperColors(android.graphics.Color.valueOf(primary), null, null, WallpaperColors.HINT_SUPPORTS_DARK_TEXT)
        Colours.listeners.toList().forEach { it.onColorsChanged(report, WallpaperManager.FLAG_SYSTEM) }
    }

    private fun folderInk(): Color {
        val node = compose.onNodeWithText(folder.name, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    @Test fun theShellPassesTheAnnouncedLookToHome() {
        showHome()
        assertThat(folderInk()).isEqualTo(Color.White)
        announceBrightWallpaper()
        assertThat(folderInk()).isEqualTo(Color.Black)
    }

    @Test fun theShellSavesTheAnnouncedLookInSettings() {
        showHome()
        var stored: WallpaperLook? = null
        compose.runOnUiThread {
            val activity = compose.activity
            val shell = ViewModelProvider(activity)[LauncherShellViewModel::class.java]
            activity.lifecycleScope.launch { shell.settings.collect { stored = it.lastHomeWallpaper } }
        }
        compose.waitUntil("the settings reader has loaded the initial wallpaper", 10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            stored != null
        }
        assertThat(stored).isEqualTo(WallpaperLook())
        announceBrightWallpaper()
        compose.waitUntil("the shell saves the announced hint and primary colour", 10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            stored == WallpaperLook(true, primary)
        }
    }
}
