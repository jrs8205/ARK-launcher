package org.arkikeskus.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import coil3.ImageLoader
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.key.Keyer
import coil3.request.Options
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.CustomIconImage
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads an icon the user picked for an app or a folder: a drawable of an installed icon pack, or a
 * gallery image saved in [files]. Null when the pack or the file is gone — the app's own icon (or
 * the folder's preview) shows then.
 */
@Singleton
class CustomIconSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iconPacks: IconPackRepository,
) {
    val files = CustomIconFiles(context)

    fun drawable(icon: CustomIcon): Drawable? = when (icon) {
        is CustomIcon.FromPack -> iconPacks.drawable(icon.pack, icon.drawable)
        is CustomIcon.FromFile -> files.load(icon)?.toDrawable(context.resources)
    }
}

/** Gallery images saved as icons, one PNG per pick in the launcher's own files (never backed up). */
class CustomIconFiles(context: Context) {
    private val dir = File(context.filesDir, "custom_icons")

    /**
     * Turns the picture at [uri] into an icon: the middle square of it, scaled to [SIZE] and cut to
     * the phone's own icon shape (the adaptive-icon mask), so it sits among the other icons like
     * one of them. Null when [uri] is no readable picture.
     */
    fun import(context: Context, uri: Uri): CustomIcon.FromFile? = runCatching {
        val picture = decodePicture(context, uri) ?: return null
        val side = minOf(picture.width, picture.height)
        val crop = Rect((picture.width - side) / 2, (picture.height - side) / 2, (picture.width + side) / 2, (picture.height + side) / 2)
        val icon = createBitmap(SIZE, SIZE)
        val canvas = Canvas(icon)
        val shape = AdaptiveIconDrawable(Color.BLACK.toDrawable(), null).apply { setBounds(0, 0, SIZE, SIZE) }.iconMask
        canvas.clipPath(shape)
        canvas.drawBitmap(picture, crop, Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        save(icon)
    }.getOrNull()

    /** Saves [bitmap] under a new name, so a new pick never reuses an old one's cached image. */
    fun save(bitmap: Bitmap): CustomIcon.FromFile {
        dir.mkdirs()
        val name = "${UUID.randomUUID()}.png"
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return CustomIcon.FromFile(name)
    }

    fun load(icon: CustomIcon.FromFile): Bitmap? =
        File(dir, icon.name).takeIf { it.isFile }?.let { file ->
            // Read through a stream closed right away, so the file can be deleted when replaced.
            runCatching { file.inputStream().use(BitmapFactory::decodeStream) }.getOrNull()
        }

    /** The picture at [uri], its short side no larger than twice [SIZE]. ImageDecoder first: it
     *  turns a camera photo upright by its EXIF orientation; BitmapFactory where it can't decode. */
    private fun decodePicture(context: Context, uri: Uri): Bitmap? {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = (SIZE * 2f) / minOf(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        if (shortSide <= 0) return null
        var sample = 1
        while (shortSide / (sample * 2) >= SIZE * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    fun delete(icon: CustomIcon.FromFile) {
        runCatching { File(dir, icon.name).delete() }
    }

    /** Deletes every saved image not named in [used] and last written before [savedBefore]. */
    fun deleteUnused(used: Set<String>, savedBefore: Long) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in used && file.lastModified() < savedBefore) runCatching { file.delete() }
        }
    }

    companion object {
        /** Side of a saved icon image (px): as crisp as the launcher's own rasterized icons. */
        const val SIZE = 192
    }
}

class CustomIconKeyer : Keyer<CustomIconImage> {
    // The epoch keeps an updated pack's old drawable from being served out of the cache.
    override fun key(data: CustomIconImage, options: Options): String = "customicon:${data.icon.encode()}:e=${data.epoch}"
}

/** Draws a [CustomIconImage] once into a bitmap, like [AppIconFetcher] does for app icons. */
class CustomIconFetcher(private val data: CustomIconImage, private val source: CustomIconSource) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        source.drawable(data.icon)?.let(::iconResult)
    }

    class Factory(private val source: CustomIconSource) : Fetcher.Factory<CustomIconImage> {
        override fun create(data: CustomIconImage, options: Options, imageLoader: ImageLoader): Fetcher =
            CustomIconFetcher(data, source)
    }
}
