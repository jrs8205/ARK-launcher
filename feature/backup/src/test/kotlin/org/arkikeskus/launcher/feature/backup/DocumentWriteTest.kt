package org.arkikeskus.launcher.feature.backup

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import android.content.pm.ProviderInfo
import java.io.File

/**
 * Overwriting an existing, larger backup in the Save picker: the document provider opens mode "w"
 * without truncating (Android 10+), so the new JSON used to be followed by the tail of the old
 * file. The export asks for a truncating write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DocumentWriteTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    /** A documents provider over one file that honours the open mode exactly as the platform does. */
    class FileProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file!!, ParcelFileDescriptor.parseMode(mode))
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    companion object {
        var file: File? = null
    }

    @Test fun anExportOverAnOlderLargerFileLeavesNoTail() {
        val file = File.createTempFile("backup", ".json", context.cacheDir).apply { writeText("x".repeat(100)) }
        Companion.file = file
        Robolectric.buildContentProvider(FileProvider::class.java).create(ProviderInfo().apply { authority = "docs" })
        val uri = Uri.parse("content://docs/backup.json")

        context.contentResolver.writeDocument(uri, "{\"v\":1}".toByteArray())

        assertThat(file.readText()).isEqualTo("{\"v\":1}")
    }
}
