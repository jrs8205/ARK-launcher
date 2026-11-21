package org.arkikeskus.launcher.feature.backup

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.backup.BackupCodec
import org.arkikeskus.launcher.data.backup.BackupDocument
import org.arkikeskus.launcher.data.backup.ExportProfileChangedException
import org.arkikeskus.launcher.data.backup.ExportedProfile
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * An export to the document the Save picker handed back. The picker hands back an EXISTING file when the user
 * agrees to write over one, and that file can be the only earlier backup there is: whatever stops the export,
 * the launcher never deletes the document it was given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DocumentExportTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://export-docs/backup.json")

    /** A documents provider over one file: it opens the file as the platform does, and deletes it when asked
     *  to, as the system's own providers do. */
    class DocumentProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file!!, ParcelFileDescriptor.parseMode(mode))
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            calls += method
            if (method == "android:deleteDocument") file!!.delete()
            return Bundle()
        }
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    companion object {
        var file: File? = null
        val calls = mutableListOf<String>()
    }

    private val earlierBackup = "{\"an earlier backup\":true}"
    private val document = BackupDocument(
        format = BackupCodec.FORMAT,
        appVersion = "1.3.0",
        createdAt = 1L,
        settings = emptyMap(),
        homeItems = emptyList(),
    )

    @Before fun aDocumentWithAnEarlierBackup() {
        file = File.createTempFile("backup", ".json", context.cacheDir).apply { writeText(earlierBackup) }
        calls.clear()
        Robolectric.buildContentProvider(DocumentProvider::class.java).create(ProviderInfo().apply { authority = "export-docs" })
    }

    /**
     * The profile was switched while the picker was open, and the user had picked an earlier backup to write
     * over. Nothing is exported, and the earlier backup is still there, whole: the launcher did not make that
     * file, so it is not the launcher's to remove.
     */
    @Test fun anExportRefusedForAnotherProfileLeavesTheChosenDocumentAsItWas() = runTest {
        val event = exportToDocument(context.contentResolver, uri) { throw ExportProfileChangedException() }

        assertThat(event).isEqualTo(BackupEvent.ExportProfileChanged)
        assertThat(file!!.exists()).isTrue()
        assertThat(file!!.readText()).isEqualTo(earlierBackup)
        assertThat(calls).doesNotContain("android:deleteDocument")
    }

    /** Nor does any other failure before the write remove it. */
    @Test fun anExportThatFailsBeforeItWritesLeavesTheChosenDocumentAsItWas() = runTest {
        val event = exportToDocument(context.contentResolver, uri) { throw IOException("the settings could not be read") }

        assertThat(event).isEqualTo(BackupEvent.Failed("the settings could not be read"))
        assertThat(file!!.readText()).isEqualTo(earlierBackup)
        assertThat(calls).isEmpty()
    }

    /** The export itself: the document replaces what the file held, and the result names the profile only
     *  when there are several. */
    @Test fun anExportWritesTheDocumentAndNamesItsProfileAmongSeveral() = runTest {
        val several = exportToDocument(context.contentResolver, uri) { write ->
            write(document)
            ExportedProfile(id = 2, name = "Work", oneOfSeveral = true)
        }
        assertThat(several).isEqualTo(BackupEvent.Exported("backup.json", profile = "Work"))
        assertThat(file!!.readText()).isEqualTo(BackupCodec.encode(document))

        val one = exportToDocument(context.contentResolver, uri) { write ->
            write(document)
            ExportedProfile(id = 1, name = "", oneOfSeveral = false)
        }
        assertThat(one).isEqualTo(BackupEvent.Exported("backup.json", profile = null))
    }
}
