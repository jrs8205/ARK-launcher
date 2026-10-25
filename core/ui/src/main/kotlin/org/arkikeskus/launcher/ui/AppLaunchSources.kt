package org.arkikeskus.launcher.ui

import android.app.ActivityOptions
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalView
import org.arkikeskus.launcher.model.AppItem
import kotlin.math.roundToInt

/** How an app launch opens: the window scales up from [sourceBounds], the icon on screen. */
class AppLaunch(val options: Bundle, val sourceBounds: android.graphics.Rect)

/**
 * One app icon's place for a launch zoom. The icon image reports where it is ([icon]); the area that
 * takes the tap — the icon itself, or a home or dock cell around it ([appLaunchTouch]) — reports the
 * finger lifting from it.
 */
class AppLaunchOrigin internal constructor(internal val sources: AppLaunchSources, internal val view: View) {
    internal var icon: LayoutCoordinates? = null
}

/**
 * The tap an app launch zooms from — what Launcher3 does when it is not the system's recents
 * provider (`makeClipRevealAnimation` / `makeScaleUpAnimation`; the full Pixel transition needs
 * remote animations, which are system-only, and so does an animation back into the icon).
 *
 * A finger lifting from an icon's tap area hands that icon to the launch the tap starts, which runs
 * in the same input dispatch: a second finger on another icon of the same app, a gesture that was
 * cancelled, or a launch that did not come from a tap (TalkBack, a search key) cannot borrow it.
 */
class AppLaunchSources {
    private class Lift(val key: String, val origin: AppLaunchOrigin, val at: Long)

    private var last: Lift? = null

    internal fun lifted(app: AppItem, origin: AppLaunchOrigin) {
        last = Lift(app.key, origin, SystemClock.uptimeMillis())
    }

    /**
     * The zoom for launching [app], or null unless a finger just lifted from one of [app]'s icons
     * (within [MAX_LIFT_AGE_MS]) and that icon is still on screen. Each lift serves one launch.
     */
    fun take(app: AppItem): AppLaunch? {
        val lift = last ?: return null
        last = null
        if (lift.key != app.key) return null
        if (SystemClock.uptimeMillis() - lift.at > MAX_LIFT_AGE_MS) return null
        val icon = lift.origin.icon?.takeIf { it.isAttached } ?: return null
        val view = lift.origin.view
        if (!view.isAttachedToWindow) return null
        val bounds = icon.boundsInRoot()
        val x = bounds.left.roundToInt()
        val y = bounds.top.roundToInt()
        val w = bounds.width.roundToInt()
        val h = bounds.height.roundToInt()
        if (w <= 0 || h <= 0) return null
        val onScreen = IntArray(2).also(view::getLocationOnScreen)
        return AppLaunch(
            options = ActivityOptions.makeScaleUpAnimation(view, x, y, w, h).toBundle(),
            sourceBounds = android.graphics.Rect(onScreen[0] + x, onScreen[1] + y, onScreen[0] + x + w, onScreen[1] + y + h),
        )
    }

    private companion object {
        /** A tap launches while its lift is being handled; anything later did not come from it. */
        const val MAX_LIFT_AGE_MS = 500L
    }
}

/** Provided once at the launcher's root; without it icons record nothing and launches do not zoom. */
val LocalAppLaunchSources = staticCompositionLocalOf<AppLaunchSources?> { null }

/** A launch origin for one icon in this window, or null outside the launcher's root. */
@Composable
fun rememberAppLaunchOrigin(): AppLaunchOrigin? {
    val sources = LocalAppLaunchSources.current ?: return null
    val view = LocalView.current
    return remember(sources, view) { AppLaunchOrigin(sources, view) }
}

/**
 * Watches this tap area of [app]'s icon: a finger lifting from it hands [origin] to the launch that
 * tap starts. Only watches — it never consumes, so the area's own gesture decides as before. A cell
 * larger than its icon (home, dock) puts it on the cell and passes the same origin to its
 * [org.arkikeskus.launcher.ui.component.AppIcon].
 */
fun Modifier.appLaunchTouch(app: AppItem, origin: AppLaunchOrigin?): Modifier =
    if (origin == null) this else pointerInput(app, origin) {
        awaitPointerEventScope {
            while (true) {
                // A real lift arrives here unconsumed: this is the first pass, before the area's own
                // gesture sees it. A cancelled gesture ends with a synthetic, already consumed lift.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.changedToUp() }) origin.sources.lifted(app, origin)
            }
        }
    }
