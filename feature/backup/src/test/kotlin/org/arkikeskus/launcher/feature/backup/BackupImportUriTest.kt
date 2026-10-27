package org.arkikeskus.launcher.feature.backup

import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.BadParcelableException
import android.os.Parcelable
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

    /** A sharer's extras are unparcelled in the launcher's own process: a class the launcher does not
     *  have must end the import quietly, not take the home screen down with it. */
    @Test fun aStreamThatCannotBeUnparcelledIsRefused() {
        val intent = object : Intent(ACTION_SEND) {
            override fun <T : Any?> getParcelableExtra(name: String?, clazz: Class<T>): T? =
                throw BadParcelableException("ClassNotFoundException when unmarshalling: com.example.Hostile")

            @Deprecated("Deprecated in Java")
            override fun <T : Parcelable?> getParcelableExtra(name: String?): T? =
                throw BadParcelableException("ClassNotFoundException when unmarshalling: com.example.Hostile")
        }.setType("application/json")

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

    /** ContentResolver strips the user prefix from the *decoded* authority, so an encoded `@` must not
     *  hide the launcher's own provider either. */
    @Test fun anEncodedUserPrefixDoesNotHideTheLaunchersOwnProvider() {
        shadowOf(context.packageManager).addOrUpdateProvider(
            ProviderInfo().apply {
                authority = "org.arkikeskus.launcher.test.files"
                packageName = context.packageName
                name = "org.arkikeskus.launcher.test.FilesProvider"
            },
        )
        val encoded = Uri.parse("content://0%40org.arkikeskus.launcher.test.files/private/settings.json")

        assertThat(backupImportUri(context, Intent(Intent.ACTION_VIEW).setDataAndType(encoded, "application/json"))).isNull()
    }
}
