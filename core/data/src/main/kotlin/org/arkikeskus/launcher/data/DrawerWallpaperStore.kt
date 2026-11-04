package org.arkikeskus.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/** The user's own picture behind the app drawer: its file and its mean relative luminance (0 dark, 1 light). */
data class DrawerWallpaper(val file: File, val luminance: Float)

/** The long side a saved drawer wallpaper is kept within: enough for any phone screen, small on disk. */
internal const val DRAWER_WALLPAPER_MAX_SIDE = 1600

/** The mean relative luminance of [pixels] (ARGB), the whole picture's lightness in one number. */
internal fun meanLuminance(pixels: IntArray): Float =
    if (pixels.isEmpty()) 0f else pixels.sumOf { Color.luminance(it).toDouble() }.toFloat() / pixels.size

/** The sample size that brings a [width] × [height] picture within [maxSide] on its long side. */
internal fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int =
    ceil(maxOf(width, height).toFloat() / maxSide).toInt().coerceAtLeast(1)

/**
 * The picture the user picked for behind the app drawer (Settings > App drawer, a Telegram request
 * after 0.9.9): kept as a JPEG of its own in the app's files, never in a backup (like the gallery
 * pictures of custom icons), with its mean lightness beside it so the drawer's text and the system
 * bars' glyphs can pick their colour over it. A new pick is saved under a new name, so an image cache
 * never shows the old one; the old file goes.
 */
@Singleton
class DrawerWallpaperStore internal constructor(
    private val context: Context,
    /** Where the files are read and written; a test hands in one it can pace. */
    private val io: CoroutineDispatcher,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, Dispatchers.IO)

    private val dir = File(context.filesDir, "drawer_wallpaper")
    private val _current = MutableStateFlow(load())

    /** The ticket of the user's latest pick or removal, taken the moment it is asked for. A pick publishes only while
     *  it still holds the latest ticket, so the last action wins whatever order the saves finish in: a removal issued
     *  during a slow pick stays a removal, and of two picks in a row the later one shows. */
    private val latest = AtomicInteger()

    /** Guards the ticket check and the publish, so a removal cannot slip between them. */
    private val publishLock = Any()

    /** The picture, or null when none is set. */
    val current: StateFlow<DrawerWallpaper?> = _current

    /** Saves the picture at [uri]; false when it cannot be read or saved, or a later pick or removal overtook it. */
    suspend fun import(uri: Uri): Boolean {
        val ticket = latest.incrementAndGet()
        return withContext(io) {
            val picture = runCatching { decode(uri) }.getOrNull() ?: return@withContext false
            save(picture, ticket)
        }
    }

    /** Saves [picture] as the drawer's wallpaper (also the test seam); false as for [import]. */
    suspend fun set(picture: Bitmap): Boolean {
        val ticket = latest.incrementAndGet()
        return withContext(io) { save(picture, ticket) }
    }

    /** Removes the picture; the drawer shows the system wallpaper through again. */
    suspend fun clear() {
        latest.incrementAndGet()
        withContext(io) {
            val old = synchronized(publishLock) { _current.value.also { _current.value = null } }
            old?.file?.delete()
            File(dir, LUMINANCE_FILE).delete()
        }
    }

    /**
     * Writes [picture] and its lightness under a new name, then publishes them if [ticket] is still the latest action.
     * A write that fails half-way (no space left, a folder in the way) leaves nothing behind and keeps the picture that
     * was there: it is reported as false, never thrown into the caller's coroutine (that took the launcher down).
     */
    private fun save(picture: Bitmap, ticket: Int): Boolean {
        dir.mkdirs()
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        val saved = runCatching {
            file.outputStream().use { out ->
                check(picture.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "the picture could not be encoded" }
            }
            val luminance = luminanceOf(picture)
            File(dir, LUMINANCE_FILE).writeText(luminance.toString())
            DrawerWallpaper(file, luminance)
        }.getOrNull()
        val old = synchronized(publishLock) {
            if (saved == null || ticket != latest.get()) return@synchronized null.also { file.delete() }
            _current.value.also { _current.value = saved }
        }
        if (_current.value != saved) return false
        old?.file?.delete()
        return true
    }

    private fun load(): DrawerWallpaper? {
        val file = dir.listFiles { f -> f.isFile && f.extension == "jpg" }?.maxByOrNull { it.lastModified() } ?: return null
        val luminance = runCatching { File(dir, LUMINANCE_FILE).readText().trim().toFloat() }.getOrDefault(0f)
        return DrawerWallpaper(file, luminance.coerceIn(0f, 1f))
    }

    /** The picture at [uri] upright (ImageDecoder reads the EXIF orientation) and within
     *  [DRAWER_WALLPAPER_MAX_SIDE]; BitmapFactory where ImageDecoder cannot decode. */
    private fun decode(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height, DRAWER_WALLPAPER_MAX_SIDE))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, DRAWER_WALLPAPER_MAX_SIDE)
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun luminanceOf(picture: Bitmap): Float {
        val small = picture.scale(SAMPLE_SIDE, SAMPLE_SIDE)
        val pixels = IntArray(SAMPLE_SIDE * SAMPLE_SIDE)
        small.getPixels(pixels, 0, SAMPLE_SIDE, 0, 0, SAMPLE_SIDE, SAMPLE_SIDE)
        return meanLuminance(pixels)
    }

    private companion object {
        const val LUMINANCE_FILE = "luminance.txt"
        const val SAMPLE_SIDE = 16
    }
}
