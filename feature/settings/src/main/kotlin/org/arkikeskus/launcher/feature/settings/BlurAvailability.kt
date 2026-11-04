package org.arkikeskus.launcher.feature.settings

import android.content.Context
import android.os.Build
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

/** Whether the wallpaper blur behind the app drawer can work on this device, and whether it does right now. */
internal enum class BlurAvailability {
    /** Android 12 or newer and the system is blurring across windows right now. */
    AVAILABLE,
    /** The system is not blurring across windows at the moment (battery saver, a tunnelled video, minimal
     *  post-processing, the developer option) on a device that supports it, or whose support is unknown:
     *  the setting stays usable, the blur returns when the system does. */
    PAUSED,
    /** Android 11: the window blur API does not exist. */
    NEEDS_ANDROID_12,
    /** Android 12 or newer, but the display stack never blurs behind a window (a Fairphone 6 report). */
    DEVICE_CANNOT,
}

/** The display stack's own word on background blur: what the window manager reads for its cross-window
 *  blur support. 0 on the Fairphone 6 (Android 16), 1 on Pixels. */
internal const val BLUR_SUPPORT_PROPERTY = "ro.surface_flinger.supports_background_blur"

/**
 * [BlurAvailability] from the facts: [sdkInt] (the API level), whether the system blurs across windows
 * right now ([crossWindowBlurEnabled], a momentary state the system also switches off in battery saver,
 * for a tunnelled video or by a developer option) and whether the device can blur at all
 * ([deviceSupportsBlur], from [BLUR_SUPPORT_PROPERTY]; null when that cannot be read). A blur that is
 * on proves the support; one that is off proves nothing by itself, so only the device's own "no" greys
 * the setting, and the rest is a pause.
 */
internal fun blurAvailability(sdkInt: Int, crossWindowBlurEnabled: Boolean, deviceSupportsBlur: Boolean?): BlurAvailability =
    when {
        sdkInt < Build.VERSION_CODES.S -> BlurAvailability.NEEDS_ANDROID_12
        crossWindowBlurEnabled -> BlurAvailability.AVAILABLE
        deviceSupportsBlur == false -> BlurAvailability.DEVICE_CANNOT
        else -> BlurAvailability.PAUSED
    }

/** [BlurAvailability] for the user's own picture behind the drawer: it is blurred inside the window (Android 12's
 *  render effects), not across windows, so a device that cannot blur behind its windows (a Fairphone 6) and a system
 *  pausing that blur still blur the picture -- only the Android version limits it. */
internal fun BlurAvailability.forOwnPicture(): BlurAvailability =
    if (this == BlurAvailability.NEEDS_ANDROID_12) this else BlurAvailability.AVAILABLE

/** [BLUR_SUPPORT_PROPERTY]'s value as the window manager reads it: 1/true = supported, 0/false = not,
 *  anything else (unset, unreadable) = unknown. */
internal fun parseBlurSupport(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
    "1", "true", "y", "yes", "on" -> true
    "0", "false", "n", "no", "off" -> false
    else -> null
}

/** A system property's value, or null when it cannot be read. The property API is not public; a
 *  refusal of any kind (a future Android could block it) just leaves the support unknown. */
internal fun systemProperty(name: String): String? = runCatching {
    val systemProperties = Class.forName("android.os.SystemProperties")
    systemProperties.getMethod("get", String::class.java).invoke(null, name) as? String
}.getOrNull()

/** Whether this device's display stack can blur behind windows, or null when unknown. */
internal fun deviceSupportsBlur(): Boolean? = parseBlurSupport(systemProperty(BLUR_SUPPORT_PROPERTY))

/** The device's [BlurAvailability], kept current while the caller is on screen (battery saver, a video
 *  and the developer option for window blurs change the momentary state live). */
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
    val supported = remember { deviceSupportsBlur() }
    fun current(crossWindowBlurEnabled: Boolean) = blurAvailability(Build.VERSION.SDK_INT, crossWindowBlurEnabled, supported)
    var availability by remember(windowManager) { mutableStateOf(current(windowManager.isCrossWindowBlurEnabled)) }
    DisposableEffect(windowManager) {
        // Called at once with the current state and again on every change.
        val listener = Consumer<Boolean> { enabled -> availability = current(enabled) }
        windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return availability
}
