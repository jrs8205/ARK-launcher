package org.arkikeskus.launcher.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

/**
 * The user's own picture behind the app drawer ([file]), filling its box like a wallpaper and blurred
 * by [blur] inside the window (Android 12 and newer; a phone whose display stack cannot blur behind
 * windows still gets this one). The file's name changes with every pick, so Coil's caches never show
 * an old picture.
 */
@Composable
fun DrawerWallpaperPicture(file: File, blur: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(file).memoryCacheKey(file.name).diskCacheKey(file.name).build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = if (blur > 0.dp) modifier.blur(blur) else modifier,
    )
}
