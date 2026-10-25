package org.arkikeskus.launcher.feature.backup

import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The file another app hands to the "Open with" / share import. The import is an exported activity,
 * so any app can start it with any intent: only a content URI from another app's provider is taken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupImportUriTest {
    private val context = RuntimeEnvironment.getApplication()
    private val file = Uri.parse("content://org.telegram.messenger.provider/media/backup.json")

    @Test fun openWithHandsTheFileAsData() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(file, "application/json")

        assertThat(backupImportUri(context, intent)).isEqualTo(file)
    }

    @Test fun shareHandsTheFileAsStream() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, file)

        assertThat(backupImportUri(context, intent)).isEqualTo(file)
    }

    @Test fun aFilePathIsRefused() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/Download/backup.json"), "application/json")

        assertThat(backupImportUri(context, intent)).isNull()
    }

    @Test fun aShareWithoutAFileIsRefused() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, """{"format":3}""")

        assertThat(backupImportUri(context, intent)).isNull()
    }

    @Test fun anotherActionIsRefused() {
        val intent = Intent(Intent.ACTION_EDIT).setDataAndType(file, "application/json")

        assertThat(backupImportUri(context, intent)).isNull()
    }

    /** Another app must not get the launcher to read one of its own providers' files for it. */
    @Test fun aFileOfTheLaunchersOwnProviderIsRefused() {
        shadowOf(context.packageManager).addOrUpdateProvider(
            ProviderInfo().apply {
                authority = "org.arkikeskus.launcher.test.files"
                packageName = context.packageName
                name = "org.arkikeskus.launcher.test.FilesProvider"
            },
        )
        val own = Uri.parse("content://org.arkikeskus.launcher.test.files/private/settings.json")
        val ownWithUser = Uri.parse("content://0@org.arkikeskus.launcher.test.files/private/settings.json")

        assertThat(backupImportUri(context, Intent(Intent.ACTION_VIEW).setDataAndType(own, "application/json"))).isNull()
        assertThat(backupImportUri(context, Intent(Intent.ACTION_VIEW).setDataAndType(ownWithUser, "application/json"))).isNull()
    }
}
