package org.arkikeskus.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Open with" and share offer the launcher for a backup file (JSON from another app's provider), and
 * only for that: not for other files, and not for a bare file path the launcher could not read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupImportActivityManifestTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val file = Uri.parse("content://org.telegram.messenger.provider/media/backup.json")

    private fun handlers(intent: Intent): List<String> =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }

    @Test fun openWithOffersTheImportForAJsonFile() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(file, "application/json")

        assertThat(handlers(intent)).containsExactly(BackupImportActivity::class.java.name)
    }

    @Test fun shareOffersTheImportForAJsonFile() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, file)

        assertThat(handlers(intent)).containsExactly(BackupImportActivity::class.java.name)
    }

    @Test fun otherFilesAreNotOffered() {
        assertThat(handlers(Intent(Intent.ACTION_VIEW).setDataAndType(file, "text/plain"))).isEmpty()
        assertThat(handlers(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, file))).isEmpty()
    }

    @Test fun aFilePathIsNotOffered() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/Download/backup.json"), "application/json")

        assertThat(handlers(intent)).isEmpty()
    }
}
