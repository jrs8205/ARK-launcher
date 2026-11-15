package org.arkikeskus.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.arkikeskus.launcher.ui.LauncherAppTheme
import org.arkikeskus.launcher.feature.settings.SETTINGS_PAGE_SCREENSAVER
import org.arkikeskus.launcher.feature.settings.SettingsScreen

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Started through the alias the screensaver names as its settings (the gear in Android's
        // screensaver choice): straight to the Screensaver page. A recreated activity keeps the page
        // it was on instead.
        val openPage = SETTINGS_PAGE_SCREENSAVER.takeIf {
            savedInstanceState == null && intent?.component?.className?.endsWith(SCREENSAVER_ALIAS) == true
        }
        setContent {
            LauncherAppTheme {
                SettingsScreen(openPage = openPage)
            }
        }
    }

    private companion object {
        /** The activity-alias of the manifest, as the last part of its class name. */
        const val SCREENSAVER_ALIAS = ".ScreensaverSettingsAlias"
    }
}
