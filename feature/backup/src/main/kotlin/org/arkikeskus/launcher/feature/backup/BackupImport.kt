package org.arkikeskus.launcher.feature.backup

import android.content.ContentResolver
import android.net.Uri
import org.arkikeskus.launcher.data.backup.BackupFormatException
import org.json.JSONException

/** Hard cap for a backup file; a real backup is a few kB, so 10 MiB is generous. */
internal const val MAX_BACKUP_BYTES = 10L * 1024 * 1024

/** Thrown when a backup file exceeds the import size cap — never buffer it whole. */
internal class BackupTooLargeException : Exception()

/**
 * The system refused this app the picked file. Since Android 12 the system's storage provider opens
 * a file for reading through MediaStore under its own name, and on a phone where that provider has
 * lost its storage access MediaStore refuses it ("com.android.externalstorage has no access to
 * content://media/…") — writing does not go that way, so exporting still works there. Picking the
 * file through another provider (Downloads, a file manager) or opening it from a file manager does.
 */
internal class BackupReadBlockedException(cause: SecurityException) : Exception(cause)

/** The picked file's text, never more than [MAX_BACKUP_BYTES]: an oversized or corrupt file must
 *  fail fast instead of OOMing the HOME process. A refusal to read it is [BackupReadBlockedException]. */
internal fun readBackupText(resolver: ContentResolver, uri: Uri): String = try {
    resolver.openInputStream(uri)?.use { readBounded(it).decodeToString() }
        ?: error("Could not open input stream")
} catch (e: SecurityException) {
    throw BackupReadBlockedException(e)
}

private fun readBounded(stream: java.io.InputStream): ByteArray {
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
    is BackupTooLargeException -> BackupEvent.TooLarge
    is BackupFormatException, is JSONException -> BackupEvent.InvalidFile
    else -> BackupEvent.Failed(error.message.orEmpty())
}
