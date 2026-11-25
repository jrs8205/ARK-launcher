package org.arkikeskus.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.arkikeskus.launcher.data.WorkProfileRepository
import org.arkikeskus.launcher.ui.LauncherAppTheme
import org.arkikeskus.launcher.ui.WorkProfileMarks
import org.arkikeskus.launcher.feature.settings.SETTINGS_PAGE_SCREENSAVER
import org.arkikeskus.launcher.feature.settings.SettingsScreen
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    @Inject
    lateinit var workProfileRepository: WorkProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Started through the alias the screensaver names as its settings (the gear in Android's
        // screensaver choice): straight to the Screensaver page. A recreated activity keeps the page
        // it was on instead.
        val openPage = when {
            savedInstanceState != null -> null
            intent?.component?.className?.endsWith(SCREENSAVER_ALIAS) == true -> SETTINGS_PAGE_SCREENSAVER
            intent?.component?.className == SettingsActivity::class.java.name -> intent.getStringExtra(EXTRA_PAGE)
            else -> null
        }
        setContent {
            LauncherAppTheme {
                // The lists of this window (hidden apps, the app pickers, the drawer's folders) show apps
                // of every profile: a work app says its profile to a screen reader here as on the home screen.
                WorkProfileMarks(workProfileRepository.workProfile) {
                    SettingsScreen(openPage = openPage)
                }
            }
        }
    }

    internal companion object {
        const val EXTRA_PAGE = "org.arkikeskus.launcher.SETTINGS_PAGE"

        /** The activity-alias of the manifest, as the last part of its class name. */
        private const val SCREENSAVER_ALIAS = ".ScreensaverSettingsAlias"
    }
}
