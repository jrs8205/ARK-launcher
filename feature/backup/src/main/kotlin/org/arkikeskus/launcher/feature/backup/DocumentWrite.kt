package org.arkikeskus.launcher.feature.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException

/**
 * Writes [bytes] as the whole content of the document [uri]. Mode "w" does not truncate on Android
 * 10+ (the file-system provider opens it without O_TRUNC), so overwriting an older, larger backup
 * from the Save picker left the tail of the old file after the new JSON. "wt" truncates; a provider
 * that rejects the mode gets the plain write.
 */
internal fun ContentResolver.writeDocument(uri: Uri, bytes: ByteArray) {
    val stream = try {
        openOutputStream(uri, "wt")
    } catch (e: FileNotFoundException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } ?: openOutputStream(uri) ?: error("Could not open output stream")
    stream.use { it.write(bytes) }
}
