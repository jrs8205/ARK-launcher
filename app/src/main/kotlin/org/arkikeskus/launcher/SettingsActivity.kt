package org.arkikeskus.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.arkikeskus.launcher.ui.LauncherAppTheme
import org.arkikeskus.launcher.feature.settings.SettingsScreen

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherAppTheme {
                SettingsScreen()
            }
        }
    }
}
