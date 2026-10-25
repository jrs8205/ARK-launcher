package org.arkikeskus.launcher.feature.backup

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.arkikeskus.launcher.data.backup.BackupFormatException
import org.json.JSONException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/** Hard cap for a backup file; a real backup is a few kB, so 10 MiB is generous. */
internal const val MAX_BACKUP_BYTES = 10L * 1024 * 1024

/** How long the picked file may take to arrive; a cloud provider on a slow network still makes it. */
internal const val READ_TIMEOUT_MS = 30_000L

/** Thrown when a backup file exceeds the import size cap — never buffer it whole. */
internal class BackupTooLargeException : Exception()

/** The picked file did not arrive within [READ_TIMEOUT_MS]. */
internal class BackupReadTimeoutException : Exception()

/**
 * The system refused this app the picked file. Since Android 12 the system's storage provider opens
 * a file for reading through MediaStore under its own name, and on a phone where that provider has
 * lost its storage access MediaStore refuses it ("com.android.externalstorage has no access to
 * content://media/…") — writing does not go that way, so exporting still works there. Picking the
 * file through another provider (Downloads, a file manager) or opening it from a file manager does.
 */
internal class BackupReadBlockedException(cause: SecurityException) : Exception(cause)

/** [readBackupText], given up after [timeoutMs] with [BackupReadTimeoutException]. */
internal suspend fun readBackupTextWithin(
    resolver: ContentResolver,
    uri: Uri,
    timeoutMs: Long = READ_TIMEOUT_MS,
): String = withTimeoutOrNull(timeoutMs) { readBackupText(resolver, uri) } ?: throw BackupReadTimeoutException()

/**
 * The picked file's text, never more than [MAX_BACKUP_BYTES]: an oversized or corrupt file must fail
 * fast instead of OOMing the HOME process. A refusal to read it is [BackupReadBlockedException].
 *
 * Opening and reading block inside the provider, which may never answer, so they run on an IO
 * thread of their own and the caller is not held: cancelling returns at once and closes the stream,
 * which ends a read blocked in it. An open the provider never returns from cannot be interrupted;
 * its thread lets go when the provider does, and closes what it got.
 */
internal suspend fun readBackupText(resolver: ContentResolver, uri: Uri): String =
    suspendCancellableCoroutine { cont ->
        val stream = AtomicReference<InputStream?>()
        cont.invokeOnCancellation { stream.getAndSet(null)?.closeQuietly() }
        Dispatchers.IO.asExecutor().execute {
            val result = runCatching {
                val input = resolver.openInputStream(uri) ?: error("Could not open input stream")
                stream.set(input)
                // Cancelled while it was opening: the handler found nothing to close.
                if (!cont.isActive) {
                    stream.getAndSet(null)?.closeQuietly()
                    throw CancellationException("Import left while the file was opening")
                }
                input.use { readBounded(it).decodeToString() }
            }.recoverCatching { if (it is SecurityException) throw BackupReadBlockedException(it) else throw it }
            // A cancelled continuation ignores this.
            cont.resumeWith(result)
        }
    }

private fun InputStream.closeQuietly() {
    runCatching { close() }
}

private fun readBounded(stream: InputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        total += n
        if (total > MAX_BACKUP_BYTES) throw BackupTooLargeException()
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** What the screen is told about a failed import. */
internal fun importFailureEvent(error: Throwable): BackupEvent = when (error) {
    is BackupReadBlockedException -> BackupEvent.ReadBlocked
    is BackupReadTimeoutException -> BackupEvent.ReadTimedOut
    is BackupTooLargeException -> BackupEvent.TooLarge
    is BackupFormatException, is JSONException -> BackupEvent.InvalidFile
    else -> BackupEvent.Failed(error.message.orEmpty())
}
