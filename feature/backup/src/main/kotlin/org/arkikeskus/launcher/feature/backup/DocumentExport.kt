package org.arkikeskus.launcher.feature.backup

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.backup.BackupCodec
import org.arkikeskus.launcher.data.backup.BackupDocument
import org.arkikeskus.launcher.data.backup.ExportedProfile

/**
 * Writes one export to [uri], the document the user picked in the system's Save picker, and tells how it went
 * as the screen shows it. [export] is the export itself: it calls the writer it is given with the document,
 * or throws without calling it when there is nothing to write (another home profile is on than the one the
 * button named).
 *
 * The document is NEVER deleted here, whatever stops the export. The picker hands back an existing file when
 * the user agrees to write over one, and nothing tells that file from one the picker has just made: it can be
 * the only earlier backup there is. An export that is refused may therefore leave the empty file of a new
 * name behind; the next export of that name writes over it.
 */
internal suspend fun exportToDocument(
    resolver: ContentResolver,
    uri: Uri,
    export: suspend (write: suspend (BackupDocument) -> Unit) -> ExportedProfile,
): BackupEvent = runCatching {
    withContext(Dispatchers.IO) {
        export { doc -> resolver.writeDocument(uri, BackupCodec.encode(doc).toByteArray()) }
    }
}.fold(
    onSuccess = { exported ->
        BackupEvent.Exported(uri.lastPathSegment ?: "", profile = exported.name.takeIf { exported.oneOfSeveral })
    },
    onFailure = {
        if (it is CancellationException) throw it
        Log.w(BackupViewModel.TAG, "File export failed", it)
        // Blank message → the screen shows its localized generic failure text.
        exportFailureEvent(it)
    },
)
