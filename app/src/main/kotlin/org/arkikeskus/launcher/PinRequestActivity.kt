package org.arkikeskus.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.arkikeskus.launcher.data.systemPinRequest
import org.arkikeskus.launcher.ui.LauncherAppTheme
import org.arkikeskus.launcher.feature.home.PinRequestScreen

/**
 * Another app's "Add to home screen" (`LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT`) or "Add widget"
 * (`ACTION_CONFIRM_PIN_APPWIDGET`): a transparent window over the requesting app with the
 * confirmation sheet. Its own activity, not [LauncherActivity]: that one is a single task that
 * would come to the front over the requesting app, and its new intents mean HOME. The system
 * starts this one with NEW_TASK|CLEAR_TASK, so a newer request replaces an open one; a request the
 * intent doesn't carry ends here without a word, as in Launcher3. It stays up while the system's
 * widget bind dialog is open, so the dialog's answer has somewhere to come back to.
 */
@AndroidEntryPoint
class PinRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = systemPinRequest(this, intent)
        if (request == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            LauncherAppTheme {
                PinRequestScreen(request = request, onFinish = ::finish)
            }
        }
    }
}
