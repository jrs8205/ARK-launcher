package org.arkikeskus.launcher.feature.backup

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Reading the picked file. On some phones the system's storage provider is itself refused by
 * MediaStore when it opens the file for reading ("com.android.externalstorage has no access to
 * content://media/…"): that refusal is told apart from every other failure, so the screen can say
 * what to do instead of echoing the system's message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupReadTest {
    private val resolver = RuntimeEnvironment.getApplication().contentResolver
    private val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fb.json")

    @Test fun readsTheWholeFile() {
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream("""{"format":3}""".toByteArray()))

        assertThat(readBackupText(resolver, uri)).isEqualTo("""{"format":3}""")
    }

    @Test fun storageRefusalWhenOpeningIsReportedAsBlocked() {
        val refusal = SecurityException("com.android.externalstorage has no access to content://media/external_primary/file/1")
        shadowOf(resolver).registerInputStreamSupplier(uri) { throw refusal }

        val thrown = assertThrows(BackupReadBlockedException::class.java) { readBackupText(resolver, uri) }

        assertThat(thrown).hasCauseThat().isSameInstanceAs(refusal)
    }

    @Test fun storageRefusalWhileReadingIsReportedAsBlocked() {
        val stream = object : InputStream() {
            override fun read(): Int = throw SecurityException("revoked")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw SecurityException("revoked")
        }
        shadowOf(resolver).registerInputStream(uri, stream)

        assertThrows(BackupReadBlockedException::class.java) { readBackupText(resolver, uri) }
    }

    @Test fun aFileOverTheCapIsTooLarge() {
        val big = object : InputStream() {
            override fun read(): Int = 'x'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { b.fill('x'.code.toByte(), off, off + len) }
        }
        shadowOf(resolver).registerInputStream(uri, big)

        assertThrows(BackupTooLargeException::class.java) { readBackupText(resolver, uri) }
    }
}
