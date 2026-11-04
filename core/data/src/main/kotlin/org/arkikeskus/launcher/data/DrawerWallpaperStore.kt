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
import java.util.Locale
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

    /** Removes the picture; the drawer shows the system wallpaper through again. A removal the pool runs only after
     *  a pick issued later stands down: that pick is the user's last action, and it clears the old file itself. */
    suspend fun clear() {
        val ticket = latest.incrementAndGet()
        withContext(io) {
            val old = synchronized(publishLock) {
                if (ticket != latest.get()) return@withContext
                File(dir, LEGACY_LUMINANCE_FILE).delete()
                _current.value.also { _current.value = null }
            }
            old?.file?.delete()
        }
    }

    /**
     * Encodes [picture] into a part-file (not a picture yet: [load] never takes one), then -- if [ticket] is still the
     * latest action -- renames it under the lock to its final name, which carries its lightness ([pictureFileName]),
     * and publishes it. The rename is atomic, so the next start can only ever load a picture that was published whole,
     * with its own lightness; a process that dies mid-save leaves a part-file that [load] sweeps. The picture this one
     * replaces is deleted whatever happens to the new one afterwards (a removal right after the publish deletes the new
     * one itself). A write that fails half-way (no space left, a picture that cannot be encoded) leaves nothing behind
     * and keeps the picture that was there: it is reported as false, never thrown into the caller's coroutine (that
     * took the launcher down).
     */
    private fun save(picture: Bitmap, ticket: Int): Boolean {
        dir.mkdirs()
        val part = File(dir, "${UUID.randomUUID()}$PART_SUFFIX")
        val luminance = runCatching {
            check(!picture.isRecycled) { "the picture was recycled" }
            part.outputStream().use { out ->
                check(picture.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "the picture could not be encoded" }
            }
            storedLuminance(luminanceOf(picture))
        }.getOrNull()
        var replaced: DrawerWallpaper? = null
        var published: DrawerWallpaper? = null
        synchronized(publishLock) {
            if (luminance == null || ticket != latest.get()) return@synchronized
            val target = File(dir, pictureFileName(luminance))
            if (!part.renameTo(target)) return@synchronized
            replaced = _current.value
            published = DrawerWallpaper(target, luminance)
            _current.value = published
        }
        if (published == null) {
            part.delete()
            return false
        }
        replaced?.file?.delete()
        return true
    }

    /** The newest published picture with the lightness in its name; part-files of saves the process did not live to
     *  publish are swept here. A picture an older build saved (no lightness in its name) reads the shared file it wrote. */
    private fun load(): DrawerWallpaper? {
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        files.filter { it.name.endsWith(PART_SUFFIX) }.forEach { it.delete() }
        val file = files.filter { it.extension == "jpg" }.maxByOrNull { it.lastModified() } ?: return null
        val luminance = luminanceInName(file.name)
            ?: runCatching { File(dir, LEGACY_LUMINANCE_FILE).readText().trim().toFloat() }.getOrDefault(0f)
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
        /** The shared lightness file of builds before the lightness moved into the picture's name. */
        const val LEGACY_LUMINANCE_FILE = "luminance.txt"
        const val PART_SUFFIX = ".part"
        const val SAMPLE_SIDE = 16
        private val LUMINANCE_IN_NAME = Regex("""-(\d\.\d{3})\.jpg$""")

        /** The lightness as the file name carries it: three decimals, the same value in memory and after a restart. */
        fun storedLuminance(luminance: Float): Float = "%.3f".format(Locale.ROOT, luminance.coerceIn(0f, 1f)).toFloat()

        /** `<uuid>-<lightness>.jpg`: a new name for every pick (no image cache shows the old one) with its lightness. */
        fun pictureFileName(luminance: Float): String = "${UUID.randomUUID()}-${"%.3f".format(Locale.ROOT, luminance)}.jpg"

        /** The lightness a published picture's name carries, or null for a name without one. */
        fun luminanceInName(name: String): Float? = LUMINANCE_IN_NAME.find(name)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
