package org.arkikeskus.launcher.feature.settings

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.function.Consumer

/** Whether the wallpaper blur behind the app drawer can work on this device. */
internal enum class BlurAvailability {
    /** Android 12 or newer and the system blurs across windows (or battery saver pauses it for now). */
    AVAILABLE,
    /** Android 11: the window blur API does not exist. */
    NEEDS_ANDROID_12,
    /** Android 12 or newer, but the display stack never blurs behind a window (a Fairphone 6 report). */
    DEVICE_CANNOT,
}

/**
 * [BlurAvailability] from the facts: [sdkInt] (the API level), whether the system blurs across windows
 * right now ([crossWindowBlurEnabled]) and whether battery saver is on ([powerSaveMode]). Battery saver
 * switches cross-window blur off only for the moment, so it does not count against the device.
 */
internal fun blurAvailability(sdkInt: Int, crossWindowBlurEnabled: Boolean, powerSaveMode: Boolean): BlurAvailability =
    when {
        sdkInt < Build.VERSION_CODES.S -> BlurAvailability.NEEDS_ANDROID_12
        crossWindowBlurEnabled || powerSaveMode -> BlurAvailability.AVAILABLE
        else -> BlurAvailability.DEVICE_CANNOT
    }

/** The device's [BlurAvailability], kept current while the caller is on screen (battery saver and the
 *  developer option for window blurs change it live). */
@Composable
internal fun rememberBlurAvailability(): BlurAvailability {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return BlurAvailability.NEEDS_ANDROID_12
    val context = LocalContext.current
    return rememberCrossWindowBlurAvailability(context)
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun rememberCrossWindowBlurAvailability(context: Context): BlurAvailability {
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    fun current(crossWindowBlurEnabled: Boolean) =
        blurAvailability(Build.VERSION.SDK_INT, crossWindowBlurEnabled, powerManager?.isPowerSaveMode == true)
    var availability by remember(windowManager) { mutableStateOf(current(windowManager.isCrossWindowBlurEnabled)) }
    DisposableEffect(windowManager) {
        // Called at once with the current state and again on every change.
        val listener = Consumer<Boolean> { enabled -> availability = current(enabled) }
        windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return availability
}
