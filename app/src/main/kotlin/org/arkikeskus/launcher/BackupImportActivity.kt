package org.arkikeskus.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.feature.backup.BackupImportScreen
import org.arkikeskus.launcher.feature.backup.backupImportUri

/**
 * A backup file opened ("Open with") or shared from another app — a file manager, a chat: a
 * transparent window over that app with the restore confirmation. The file comes through that app's
 * provider with its read grant, so a file manager app or Downloads gets round phones where the
 * system's storage provider is refused the file (`BackupReadBlockedException`); a file opened from
 * that same provider is refused here too. Like [PinRequestActivity] it is not [LauncherActivity], whose
 * new intents mean HOME; started with NEW_TASK it gets a task of its own, kept out of recents. An
 * intent without a usable file ([backupImportUri]) ends here without a word.
 */
@AndroidEntryPoint
class BackupImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = backupImportUri(this, intent)
        if (uri == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            LauncherTheme {
                BackupImportScreen(uri = uri, onFinish = ::finish)
            }
        }
    }
}
