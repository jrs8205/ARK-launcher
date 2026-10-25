package org.arkikeskus.launcher.feature.backup

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reading the picked file. On some phones the system's storage provider is itself refused by
 * MediaStore when it opens the file for reading ("com.android.externalstorage has no access to
 * content://media/…"): that refusal is told apart from every other failure, so the screen can say
 * what to do instead of echoing the system's message. A provider that never answers must not keep
 * the import waiting: the read gives up after a time limit, or at once when cancelled, and closes
 * the file so a blocked read ends too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupReadTest {
    private val resolver = RuntimeEnvironment.getApplication().contentResolver
    private val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fb.json")

    /** A file whose provider never sends anything: a read blocks until the stream is closed. */
    private class SilentStream : InputStream() {
        val closed = CountDownLatch(1)
        override fun read(): Int = block()
        override fun read(b: ByteArray, off: Int, len: Int): Int = block()
        private fun block(): Int {
            closed.await()
            throw IOException("closed while reading")
        }
        override fun close() = closed.countDown()
    }

    @Test fun readsTheWholeFile() = runBlocking {
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream("""{"format":3}""".toByteArray()))

        assertThat(readBackupText(resolver, uri)).isEqualTo("""{"format":3}""")
    }

    @Test fun storageRefusalWhenOpeningIsReportedAsBlocked() {
        val refusal = SecurityException("com.android.externalstorage has no access to content://media/external_primary/file/1")
        shadowOf(resolver).registerInputStreamSupplier(uri) { throw refusal }

        val thrown = assertThrows(BackupReadBlockedException::class.java) { runBlocking { readBackupText(resolver, uri) } }

        assertThat(thrown).hasCauseThat().isSameInstanceAs(refusal)
    }

    @Test fun storageRefusalWhileReadingIsReportedAsBlocked() {
        val stream = object : InputStream() {
            override fun read(): Int = throw SecurityException("revoked")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw SecurityException("revoked")
        }
        shadowOf(resolver).registerInputStream(uri, stream)

        assertThrows(BackupReadBlockedException::class.java) { runBlocking { readBackupText(resolver, uri) } }
    }

    @Test fun aFileOverTheCapIsTooLarge() {
        val big = object : InputStream() {
            override fun read(): Int = 'x'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { b.fill('x'.code.toByte(), off, off + len) }
        }
        shadowOf(resolver).registerInputStream(uri, big)

        assertThrows(BackupTooLargeException::class.java) { runBlocking { readBackupText(resolver, uri) } }
    }

    @Test fun aFileThatNeverArrivesTimesOutAndIsClosed() {
        val silent = SilentStream()
        shadowOf(resolver).registerInputStream(uri, silent)

        assertThrows(BackupReadTimeoutException::class.java) {
            runBlocking { readBackupTextWithin(resolver, uri, timeoutMs = 100) }
        }
        assertThat(silent.closed.await(2, TimeUnit.SECONDS)).isTrue()
    }

    /** Leaving the import while the file is still being read returns at once, blocked read or not. */
    @Test fun cancellingTheReadReturnsAtOnceAndClosesTheFile() = runBlocking {
        val silent = SilentStream()
        shadowOf(resolver).registerInputStream(uri, silent)

        val read = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { readBackupText(resolver, uri) }
        Thread.sleep(100)
        read.cancel()
        withTimeout(2_000) { read.join() }

        assertThat(read.isCancelled).isTrue()
        assertThat(silent.closed.await(2, TimeUnit.SECONDS)).isTrue()
    }
}
