package org.arkikeskus.launcher.ui

import android.app.ActivityOptions
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import org.arkikeskus.launcher.model.AppItem
import kotlin.math.roundToInt

/** How an app launch opens: the window scales up from [sourceBounds], the icon on screen. */
class AppLaunch(val options: Bundle, val sourceBounds: android.graphics.Rect)

/**
 * The app icon the finger last landed on, so the app opens with a zoom from that icon — what
 * Launcher3 does when it is not the system's recents provider (`makeClipRevealAnimation` /
 * `makeScaleUpAnimation`; the full Pixel transition needs remote animations, which are system-only,
 * and so does an animation back into the icon). Every [org.arkikeskus.launcher.ui.component.AppIcon]
 * reports a touch on it; the screen that launches takes it for the app it launches.
 */
class AppLaunchSources {
    private class Touch(val key: String, val view: View, val bounds: Rect, val at: Long)

    private var last: Touch? = null

    internal fun record(app: AppItem, view: View, boundsInView: Rect) {
        last = Touch(app.key, view, boundsInView, SystemClock.uptimeMillis())
    }

    /**
     * The zoom for launching [app], or null when the last touch was not on [app]'s icon, is older
     * than [MAX_TOUCH_AGE_MS] or its icon is gone — a launch from TalkBack, a search key, or a touch
     * long past then opens without one instead of from a wrong place. Each touch serves one launch.
     */
    fun take(app: AppItem): AppLaunch? {
        val touch = last ?: return null
        last = null
        if (touch.key != app.key || !touch.view.isAttachedToWindow) return null
        if (SystemClock.uptimeMillis() - touch.at > MAX_TOUCH_AGE_MS) return null
        val x = touch.bounds.left.roundToInt()
        val y = touch.bounds.top.roundToInt()
        val w = touch.bounds.width.roundToInt()
        val h = touch.bounds.height.roundToInt()
        if (w <= 0 || h <= 0) return null
        val onScreen = IntArray(2).also(touch.view::getLocationOnScreen)
        return AppLaunch(
            options = ActivityOptions.makeScaleUpAnimation(touch.view, x, y, w, h).toBundle(),
            sourceBounds = android.graphics.Rect(onScreen[0] + x, onScreen[1] + y, onScreen[0] + x + w, onScreen[1] + y + h),
        )
    }

    private companion object {
        /** A tap launches on the finger's lift; a launch this long after the touch did not come from it. */
        const val MAX_TOUCH_AGE_MS = 10_000L
    }
}

/** Provided once at the launcher's root; without it icons record nothing and launches do not zoom. */
val LocalAppLaunchSources = staticCompositionLocalOf<AppLaunchSources?> { null }
