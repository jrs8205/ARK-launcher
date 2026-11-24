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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.di.ApplicationScope
import org.arkikeskus.launcher.data.profile.ActiveHomeProfile
import org.arkikeskus.launcher.model.HomeProfile
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
 * The picture the store shows with the home profile it is of. The store follows a profile switch a moment after the
 * settings do, so a screen that is about one profile asks for that profile's picture ([of]) and never takes another's
 * for its own.
 */
data class ShownDrawerWallpaper(val profile: Long, val picture: DrawerWallpaper?) {
    /** The picture when it is the home profile [profile]'s; null, as for no picture, while the store shows another's. */
    fun of(profile: Long): DrawerWallpaper? = picture.takeIf { this.profile == profile }
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
    /** The files in a folder, or null when the folder could not be read ([File.listFiles]). */
    private val filesIn: (File) -> Array<File>? = { it.listFiles() },
    /** Removes a folder with what is in it; false when something of it is still there ([File.deleteRecursively]). */
    private val remove: (File) -> Boolean = { it.deleteRecursively() },
) {
    /** The launcher's own store: its picture is that of the home profile that is on. */
    @Inject constructor(
        @ApplicationContext context: Context,
        active: ActiveHomeProfile,
        @ApplicationScope scope: CoroutineScope,
    ) : this(context, Dispatchers.IO) {
        scope.launch { active.id.collect { switchTo(it) } }
    }

    /** A store that stays with the first home profile until it is told otherwise ([switchTo]). */
    constructor(context: Context) : this(context, Dispatchers.IO)

    /** The home profile whose picture shows: the first until [switchTo] says otherwise, which is what there was
     *  before profiles and what a start comes up with. Changed only under [publishLock]. */
    @Volatile private var shownProfile = HomeProfile.FIRST_ID

    private val _shown = MutableStateFlow(ShownDrawerWallpaper(shownProfile, atStart()))
    private val _current = MutableStateFlow(_shown.value.picture)

    /** The ticket of the user's latest pick or removal in each home profile, taken the moment it is asked for. A pick
     *  publishes only while it still holds its profile's latest ticket, so the last action wins whatever order the
     *  saves finish in: a removal issued during a slow pick stays a removal, and of two picks in a row the later one
     *  shows. One count per profile: an action in one profile never overtakes an action in another. */
    private val latest = ConcurrentHashMap<Long, AtomicInteger>()

    private fun ticketsOf(profile: Long): AtomicInteger = latest.computeIfAbsent(profile) { AtomicInteger() }

    /** Guards the ticket check and the publish, so a removal cannot slip between them. */
    private val publishLock = Any()

    /** The picture of the home profile that shows, or null when it has none. */
    val current: StateFlow<DrawerWallpaper?> = _current

    /** [current] with the home profile it is of: for a screen that is about one profile ([ShownDrawerWallpaper.of]). */
    val shown: StateFlow<ShownDrawerWallpaper> = _shown

    /** Both values of what shows, under [publishLock]: the pair first, which is what tells whose picture it is. */
    private fun publish(profile: Long, picture: DrawerWallpaper?) {
        _shown.value = ShownDrawerWallpaper(profile, picture)
        _current.value = picture
    }

    /**
     * Saves the picture at [uri] as the home profile [profile]'s; false when it cannot be read or saved, or a later
     * pick or removal in that profile overtook it. The caller names the profile its page is about: the one this
     * store shows follows a switch a moment late, and what shows is not what the user was looking at then.
     */
    suspend fun import(uri: Uri, profile: Long): Boolean = import(uri, pick(profile))

    /** Saves [picture] as the drawer's wallpaper in the home profile [profile] (also the test seam); false as for
     *  [import]. */
    suspend fun set(picture: Bitmap, profile: Long): Boolean = set(picture, pick(profile))

    /**
     * A pick's place among the user's picks and removals in the home profile [profile]'s picture ([latest]).
     * Taken with [pick] the moment the user's choice arrives, before anything waits, and handed to [import] or
     * [set]: what the user does to that picture AFTER this moment (a removal, another pick) overtakes the
     * pick, however long the way to its save is.
     */
    class Pick internal constructor(val profile: Long, internal val ticket: Int, internal val stageOnly: Boolean) {
        internal var prepared: DrawerWallpaper? = null
    }

    /** The place of a pick made now for the home profile [profile] ([Pick]). */
    fun pick(profile: Long): Pick = Pick(profile, ticketsOf(profile).incrementAndGet(), stageOnly = false)

    /** Reserve the user's place now, but leave the old picture visible until the caller confirms the profile. */
    internal fun stagedPick(profile: Long): Pick = Pick(profile, ticketsOf(profile).incrementAndGet(), stageOnly = true)

    internal suspend fun publishPrepared(pick: Pick): Boolean = withContext(io) {
        synchronized(publishLock) { publishPreparedLocked(pick) }
    }

    internal suspend fun discardPrepared(pick: Pick) = withContext(io) {
        synchronized(publishLock) {
            pick.prepared?.file?.delete()
            pick.prepared = null
        }
    }

    /** [import] for a pick whose place was taken earlier ([pick]). */
    suspend fun import(uri: Uri, pick: Pick): Boolean = withContext(io) {
        val picture = runCatching { decode(uri) }.getOrNull() ?: return@withContext false
        save(picture, pick)
    }

    /** [set] for a pick whose place was taken earlier ([pick]). */
    suspend fun set(picture: Bitmap, pick: Pick): Boolean = withContext(io) { save(picture, pick) }

    /** Removes the picture of the home profile [profile]; its drawer shows the system wallpaper through again. Every
     *  published picture on disk goes, not only the current one: a crash between a pick's rename and the deletion of
     *  the picture it replaced leaves two, and the next start would otherwise bring the older one back after the
     *  removal. A removal the pool runs only after a pick issued later stands down: that pick is the user's last
     *  action, and it clears the old file itself. */
    suspend fun clear(profile: Long) {
        val ticket = ticketsOf(profile).incrementAndGet()
        withContext(io) {
            synchronized(publishLock) {
                if (ticket != ticketsOf(profile).get()) return@withContext
                val folder = dirOf(profile)
                File(folder, LEGACY_LUMINANCE_FILE).delete()
                if (profile == shownProfile) publish(profile, null)
                publishedPictures(folder).forEach { it.delete() }
            }
        }
    }

    /** The folder of the home profile [profile]'s picture: the first profile keeps the one there always was. */
    private fun dirOf(profile: Long): File =
        File(context.filesDir, if (profile == HomeProfile.FIRST_ID) FIRST_DIR else "${FIRST_DIR}_$profile")

    /**
     * Shows the picture of the home profile [profile] from now on. A pick or a removal still on its way names its
     * own profile and lands there, whichever profile shows by then.
     */
    suspend fun switchTo(profile: Long) = withContext(io) {
        synchronized(publishLock) {
            if (profile == shownProfile) return@withContext
            shownProfile = profile
            publish(profile, load(dirOf(profile)))
        }
    }

    /**
     * Gives the home profile [to] a copy of [from]'s picture, when [from] has one: a profile made as a copy. The
     * file keeps its name, which carries its lightness. False when the picture could not be copied whole (no
     * space left) or [from]'s folder could not be read: nothing of it is left then. As a pick, the copy is written to a part-file (not a picture
     * yet) and renamed when it is whole: written straight under the picture's name, half a file was taken for
     * the profile's picture.
     */
    suspend fun copy(from: Long, to: Long): Boolean = withContext(io) {
        val folder = dirOf(from)
        // No folder: the profile never had a picture (a pick makes the folder), and that is no failure.
        if (!folder.exists()) return@withContext true
        // A folder that is there and gives no listing could not be READ: that is not a profile without a
        // picture. Answered as one, the copy of the profile was reported as made without the picture the
        // user had chosen for its source (the check before the copy can list a folder this one cannot).
        val files = filesIn(folder) ?: return@withContext false
        val source = files.filter { it.isFile && it.extension == "jpg" }.maxByOrNull { it.lastModified() }
            ?: return@withContext true
        val target = dirOf(to)
        val part = File(target, "${UUID.randomUUID()}$PART_SUFFIX")
        val copied = runCatching {
            check(target.isDirectory || target.mkdirs()) { "the picture's folder could not be made" }
            source.copyTo(part, overwrite = true)
            // A picture an older build saved has its lightness in the shared file beside it.
            File(dirOf(from), LEGACY_LUMINANCE_FILE).takeIf { it.isFile }?.copyTo(File(target, LEGACY_LUMINANCE_FILE), overwrite = true)
            synchronized(publishLock) { check(part.renameTo(File(target, source.name))) { "the copy could not be named" } }
        }.isSuccess
        if (!copied) part.delete()
        copied
    }

    /** The home profiles a picture is kept for, whether the list of profiles names them or not. Throws an
     *  [IOException] when the folders cannot be listed: "none" must not be said of what could not be read
     *  (an id would be given to a new profile over a picture that is kept under it). */
    suspend fun profilesWithPictures(): Set<Long> = withContext(io) {
        val folders = context.filesDir.listFiles() ?: throw IOException("the pictures' folders could not be listed")
        folders.mapNotNullTo(HashSet()) { dir ->
            val id = when {
                dir.name == FIRST_DIR -> HomeProfile.FIRST_ID
                dir.name.startsWith(FIRST_DIR + "_") -> dir.name.removePrefix(FIRST_DIR + "_").toLongOrNull()
                else -> null
            }
            id?.takeIf {
                dir.isDirectory &&
                    (dir.listFiles() ?: throw IOException("a profile's pictures could not be listed")).any { it.extension == "jpg" }
            }
        }
    }

    /**
     * Removes the picture of the home profile [profile] with its folder: the profile was deleted. A pick or a
     * removal on its way there loses its ticket: nothing is saved for a profile that is gone.
     *
     * False when something of the folder is still on the disk (a file that could not be removed); true when
     * it is gone, or was never there. The caller decides what that means: a deletion that went on regardless
     * took the profile out of the list and left its picture under an id nothing names any more.
     */
    suspend fun delete(profile: Long): Boolean = withContext(io) {
        val gone = dirOf(profile)
        synchronized(publishLock) {
            ticketsOf(profile).incrementAndGet()
            if (profile == shownProfile) publish(profile, null)
            remove(gone)
        }
    }

    /**
     * Encodes [picture] into its own part-file in the staging folder (not a picture yet: [load] never takes
     * one), then -- if the ticket is still that profile's latest action -- renames it under the lock to its final name,
     * which carries its lightness ([pictureFileName]), and shows it when its profile is the one that shows. The rename
     * is atomic, so a start can only ever load a picture that was published whole, with its own lightness; a process
     * that dies mid-save leaves a part-file that the next start sweeps ([atStart]). The pictures this one replaces go
     * under the same lock, so a store that turns to this profile right then finds one picture, the new one. A write
     * that fails half-way (no space left, a picture that cannot be encoded) leaves nothing behind and keeps the
     * picture that was there: it is reported as false, never thrown into the caller's coroutine (that took the
     * launcher down).
     */
    private fun save(picture: Bitmap, pick: Pick): Boolean {
        // Staging outside the profile lets deletion remove its folder even while an encoder is running.
        // The startup sweep also covers this folder and its abandoned part-files.
        val folder = File(context.filesDir, "${FIRST_DIR}_pending")
        folder.mkdirs()
        val part = File(folder, "${UUID.randomUUID()}$PART_SUFFIX")
        val lightness = runCatching {
            check(!picture.isRecycled) { "the picture was recycled" }
            part.outputStream().use { out ->
                check(picture.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "the picture could not be encoded" }
            }
            lightnessOf(picture)
        }.getOrNull()
        return synchronized(publishLock) {
            if (lightness == null || pick.ticket != ticketsOf(pick.profile).get()) {
                part.delete()
                return@synchronized false
            }
            pick.prepared?.file?.delete()
            pick.prepared = DrawerWallpaper(part, storedLuminance(lightness.mean), lightness)
            pick.stageOnly || publishPreparedLocked(pick)
        }
    }

    private fun publishPreparedLocked(pick: Pick): Boolean {
        val ready = pick.prepared ?: return false
        pick.prepared = null
        val folder = dirOf(pick.profile)
        if (pick.ticket != ticketsOf(pick.profile).get() || (!folder.isDirectory && !folder.mkdirs())) {
            ready.file.delete()
            return false
        }
        val replaced = publishedPictures(folder)
        val target = File(folder, pictureFileName(ready.luminance))
        if (!ready.file.renameTo(target)) {
            ready.file.delete()
            return false
        }
        if (pick.profile == shownProfile) publish(pick.profile, DrawerWallpaper(target, ready.luminance, ready.lightness))
        replaced.forEach { it.delete() }
        return true
    }

    /**
     * What a start comes up with: the first home profile's picture, after the part-files a dead process left in
     * the staging folder or any profile folder are swept. Only here, where no save of this process can be under way: a later [load] (the
     * store turning to a profile) may run while a pick for that very profile is being written.
     */
    private fun atStart(): DrawerWallpaper? {
        context.filesDir.listFiles { f -> f.isDirectory && (f.name == FIRST_DIR || f.name.startsWith("${FIRST_DIR}_")) }
            ?.forEach { folder -> folder.listFiles { f -> f.isFile && f.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() } }
        return load(dirOf(HomeProfile.FIRST_ID))
    }

    /** The newest published picture in [dir] with the lightness in its name. Swept here: any older published picture
     *  a crash left beside the newest (between a pick's rename and the deletion of the pictures it replaced), so a
     *  later removal has nothing to leave behind. A picture an older build saved (no lightness in its name) reads the
     *  shared file it wrote. The lightness grid (what the drawer's crop of the picture looks like) comes from a small
     *  decode of the file; failing that, the whole picture's lightness stands for every crop. */
    private fun load(dir: File): DrawerWallpaper? {
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        val pictures = files.filter { it.extension == "jpg" }.sortedByDescending { it.lastModified() }
        val file = pictures.firstOrNull() ?: return null
        pictures.drop(1).forEach { it.delete() }
        val luminance = luminanceInName(file.name)
            ?: runCatching { File(dir, LEGACY_LUMINANCE_FILE).readText().trim().toFloat() }.getOrDefault(0f)
        val lightness = runCatching { decodeSmall(file)?.let { lightnessOf(it) } }.getOrNull()
        return DrawerWallpaper(file, luminance.coerceIn(0f, 1f), lightness)
    }

    /** [file] decoded within [LIGHTNESS_DECODE_SIDE] on its long side (the JPEG decoder scales as it reads), or null.
     *  Read through streams closed here: the file may be deleted right after (its profile deleted, a new pick), and
     *  a reader left open would be in the way of that where an open file cannot be removed. */
    private fun decodeSmall(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, LIGHTNESS_DECODE_SIDE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Every published picture in [dir]: the current one and, after a crash, the one it replaced. */
    private fun publishedPictures(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && it.extension == "jpg" }.orEmpty()

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

        /** The first home profile's folder, the one there always was; another profile's is this with its id. */
        const val FIRST_DIR = "drawer_wallpaper"
        private val LUMINANCE_IN_NAME = Regex("""-(\d\.\d{3})\.jpg$""")

        /** The lightness as the file name carries it: three decimals, the same value in memory and after a restart. */
        fun storedLuminance(luminance: Float): Float = "%.3f".format(Locale.ROOT, luminance.coerceIn(0f, 1f)).toFloat()

        /** `<uuid>-<lightness>.jpg`: a new name for every pick (no image cache shows the old one) with its lightness. */
        fun pictureFileName(luminance: Float): String = "${UUID.randomUUID()}-${"%.3f".format(Locale.ROOT, luminance)}.jpg"

        /** The lightness a published picture's name carries, or null for a name without one. */
        fun luminanceInName(name: String): Float? = LUMINANCE_IN_NAME.find(name)?.groupValues?.get(1)?.toFloatOrNull()
    }
}
