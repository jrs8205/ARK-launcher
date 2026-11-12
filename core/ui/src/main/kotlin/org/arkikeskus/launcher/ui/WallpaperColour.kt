package org.arkikeskus.launcher.ui

import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The system wallpaper's main colour (ARGB) as the launcher knows it right now: [initial] (the last
 * one known, so the first frame is already in it) until the first read answers, then what Android
 * reports, null for a wallpaper that reports none. A change announced while the first read is on
 * its way is the newer of the two and stands; a read that fails keeps what was known.
 */
class WallpaperArgbState(initial: Int?) {
    var argb: Int? by mutableStateOf(initial)
        private set
    private var announced = false

    fun onAnnounced(argb: Int?) {
        announced = true
        this.argb = argb
    }

    fun onFirstRead(read: Result<Int?>) {
        if (!announced && read.isSuccess) argb = read.getOrNull()
    }
}

/** The system wallpaper's main colour, following wallpaper changes; see [WallpaperArgbState]. */
@Composable
fun rememberWallpaperArgb(initial: Int?): Int? {
    val context = LocalContext.current
    val state = remember { WallpaperArgbState(initial) }
    DisposableEffect(context) {
        val manager = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) state.onAnnounced(colors?.primaryColor?.toArgb())
        }
        runCatching { manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { manager.removeOnColorsChangedListener(listener) } }
    }
    LaunchedEffect(context) {
        // The first read may extract the colours from the image: off the main thread.
        val read = withContext(Dispatchers.IO) {
            runCatching {
                WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
            }
        }
        state.onFirstRead(read)
    }
    return state.argb
}
