package org.arkikeskus.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
        is CustomIcon.FromFile -> files.load(icon)?.let { BitmapDrawable(context.resources, it) }
    }
}

/** Gallery images saved as icons, one PNG per pick in the launcher's own files (never backed up). */
class CustomIconFiles(context: Context) {
    private val dir = File(context.filesDir, "custom_icons")

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

    fun delete(icon: CustomIcon.FromFile) {
        runCatching { File(dir, icon.name).delete() }
    }
}

class CustomIconKeyer : Keyer<CustomIconImage> {
    override fun key(data: CustomIconImage, options: Options): String = "customicon:${data.icon.encode()}"
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
