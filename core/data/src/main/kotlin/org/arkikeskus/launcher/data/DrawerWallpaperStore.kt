package org.arkikeskus.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
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
import kotlin.math.roundToInt

/**
 * The user's own picture behind the app drawer: its file and its lightness -- [luminance], the whole picture's mean
 * relative luminance (0 dark, 1 light) as the file's name carries it, and [luminanceFor] the part the drawer shows.
 * Two values are the same picture when the file and the stored lightness agree; the grid is derived from the file.
 */
class DrawerWallpaper(val file: File, val luminance: Float, internal val lightness: PictureLightness? = null) {
    /** The mean relative luminance of the part a view of [viewAspect] (width / height) shows: the centre, at the view's
     *  aspect, as ContentScale.Crop cuts it -- a wide picture with a black middle is black on a tall phone whatever its
     *  sides are (the whole picture's mean put dark text and dark glyphs on black). The whole picture's [luminance]
     *  while the grid is not known. */
    fun luminanceFor(viewAspect: Float): Float = lightness?.shown(viewAspect) ?: luminance

    override fun equals(other: Any?): Boolean = other is DrawerWallpaper && other.file == file && other.luminance == luminance
    override fun hashCode(): Int = 31 * file.hashCode() + luminance.hashCode()
    override fun toString(): String = "DrawerWallpaper(file=$file, luminance=$luminance)"
}

/**
 * A picture's lightness over a [SIDE] x [SIDE] grid of its area ([cells]: row-major mean relative luminance per cell),
 * with the picture's [width] and [height] to tell which cells a view shows.
 */
class PictureLightness(val width: Int, val height: Int, val cells: FloatArray) {
    init {
        require(cells.size == SIDE * SIDE) { "a lightness grid has ${SIDE * SIDE} cells" }
    }

    /** The mean over the whole picture. */
    val mean: Float get() = cells.average().toFloat()

    /** The mean over the centre crop a view of [viewAspect] (width / height) shows: the picture scaled to cover the
     *  view and the overflow cut evenly from both sides (ContentScale.Crop) -- the side columns of a picture wider
     *  than the view, the top and bottom rows of one taller. The whole picture for no view ([viewAspect] 0 or NaN). */
    fun shown(viewAspect: Float): Float {
        if (!(viewAspect > 0f) || width <= 0 || height <= 0) return mean
        val pictureAspect = width.toFloat() / height
        var x0 = 0
        var x1 = SIDE
        var y0 = 0
        var y1 = SIDE
        if (pictureAspect > viewAspect) {
            val shownColumns = (SIDE * viewAspect / pictureAspect).roundToInt().coerceIn(1, SIDE)
            x0 = (SIDE - shownColumns) / 2
            x1 = x0 + shownColumns
        } else if (pictureAspect < viewAspect) {
            val shownRows = (SIDE * pictureAspect / viewAspect).roundToInt().coerceIn(1, SIDE)
            y0 = (SIDE - shownRows) / 2
            y1 = y0 + shownRows
        }
        var sum = 0.0
        for (y in y0 until y1) for (x in x0 until x1) sum += cells[y * SIDE + x]
        return (sum / ((x1 - x0) * (y1 - y0))).toFloat()
    }

    companion object {
        const val SIDE = 32
    }
}

/** The lightness grid of a [width] x [height] picture's [pixels] (ARGB, row-major): each cell the mean relative
 *  luminance of its own patch, sampled at most [SAMPLES_PER_CELL_SIDE] times per side so a full-size save stays cheap. */
internal fun pictureLightness(pixels: IntArray, width: Int, height: Int): PictureLightness {
    val side = PictureLightness.SIDE
    val cells = FloatArray(side * side)
    val stepX = maxOf(1, width / (side * SAMPLES_PER_CELL_SIDE))
    val stepY = maxOf(1, height / (side * SAMPLES_PER_CELL_SIDE))
    for (cy in 0 until side) {
        val y0 = minOf(cy * height / side, height - 1)
        val y1 = maxOf(y0 + 1, minOf((cy + 1) * height / side, height))
        for (cx in 0 until side) {
            val x0 = minOf(cx * width / side, width - 1)
            val x1 = maxOf(x0 + 1, minOf((cx + 1) * width / side, width))
            var sum = 0.0
            var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    sum += Color.luminance(pixels[y * width + x])
                    n++
                    x += stepX
                }
                y += stepY
            }
            cells[cy * side + cx] = if (n == 0) 0f else (sum / n).toFloat()
        }
    }
    return PictureLightness(width, height, cells)
}

private const val SAMPLES_PER_CELL_SIDE = 4

/** The long side a picture is decoded to for its lightness grid at start-up: a few pixels per cell. */
private const val LIGHTNESS_DECODE_SIDE = 128

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

    /** Removes the picture; the drawer shows the system wallpaper through again. Every published picture on disk goes,
     *  not only the current one: a crash between a pick's rename and the deletion of the picture it replaced leaves two,
     *  and the next start would otherwise bring the older one back after the removal. A removal the pool runs only
     *  after a pick issued later stands down: that pick is the user's last action, and it clears the old file itself. */
    suspend fun clear() {
        val ticket = latest.incrementAndGet()
        withContext(io) {
            synchronized(publishLock) {
                if (ticket != latest.get()) return@withContext
                File(dir, LEGACY_LUMINANCE_FILE).delete()
                _current.value = null
                publishedPictures().forEach { it.delete() }
            }
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
        val lightness = runCatching {
            check(!picture.isRecycled) { "the picture was recycled" }
            part.outputStream().use { out ->
                check(picture.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "the picture could not be encoded" }
            }
            lightnessOf(picture)
        }.getOrNull()
        val luminance = lightness?.let { storedLuminance(it.mean) }
        var replaced: DrawerWallpaper? = null
        var published: DrawerWallpaper? = null
        synchronized(publishLock) {
            if (luminance == null || ticket != latest.get()) return@synchronized
            val target = File(dir, pictureFileName(luminance))
            if (!part.renameTo(target)) return@synchronized
            replaced = _current.value
            published = DrawerWallpaper(target, luminance, lightness)
            _current.value = published
        }
        if (published == null) {
            part.delete()
            return false
        }
        replaced?.file?.delete()
        return true
    }

    /** The newest published picture with the lightness in its name. Swept here: part-files of saves the process did
     *  not live to publish, and any older published picture a crash left beside the newest (between a pick's rename
     *  and the deletion of the picture it replaced), so a later removal has nothing to leave behind. A picture an
     *  older build saved (no lightness in its name) reads the shared file it wrote. The lightness grid (what the
     *  drawer's crop of the picture looks like) comes from a small decode of the file; failing that, the whole
     *  picture's lightness stands for every crop. */
    private fun load(): DrawerWallpaper? {
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        files.filter { it.name.endsWith(PART_SUFFIX) }.forEach { it.delete() }
        val pictures = files.filter { it.extension == "jpg" }.sortedByDescending { it.lastModified() }
        val file = pictures.firstOrNull() ?: return null
        pictures.drop(1).forEach { it.delete() }
        val luminance = luminanceInName(file.name)
            ?: runCatching { File(dir, LEGACY_LUMINANCE_FILE).readText().trim().toFloat() }.getOrDefault(0f)
        val lightness = runCatching { decodeSmall(file)?.let { lightnessOf(it) } }.getOrNull()
        return DrawerWallpaper(file, luminance.coerceIn(0f, 1f), lightness)
    }

    /** [file] decoded within [LIGHTNESS_DECODE_SIDE] on its long side (the JPEG decoder scales as it reads), or null. */
    private fun decodeSmall(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, LIGHTNESS_DECODE_SIDE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    /** Every published picture on disk: the current one and, after a crash, the one it replaced. */
    private fun publishedPictures(): List<File> = dir.listFiles()?.filter { it.isFile && it.extension == "jpg" }.orEmpty()

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

    /** The lightness grid of [picture] ([pictureLightness]) from its own pixels. */
    private fun lightnessOf(picture: Bitmap): PictureLightness {
        val pixels = IntArray(picture.width * picture.height)
        picture.getPixels(pixels, 0, picture.width, 0, 0, picture.width, picture.height)
        return pictureLightness(pixels, picture.width, picture.height)
    }

    private companion object {
        /** The shared lightness file of builds before the lightness moved into the picture's name. */
        const val LEGACY_LUMINANCE_FILE = "luminance.txt"
        const val PART_SUFFIX = ".part"
        private val LUMINANCE_IN_NAME = Regex("""-(\d\.\d{3})\.jpg$""")

        /** The lightness as the file name carries it: three decimals, the same value in memory and after a restart. */
        fun storedLuminance(luminance: Float): Float = "%.3f".format(Locale.ROOT, luminance.coerceIn(0f, 1f)).toFloat()

        /** `<uuid>-<lightness>.jpg`: a new name for every pick (no image cache shows the old one) with its lightness. */
        fun pictureFileName(luminance: Float): String = "${UUID.randomUUID()}-${"%.3f".format(Locale.ROOT, luminance)}.jpg"

        /** The lightness a published picture's name carries, or null for a name without one. */
        fun luminanceInName(name: String): Float? = LUMINANCE_IN_NAME.find(name)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
