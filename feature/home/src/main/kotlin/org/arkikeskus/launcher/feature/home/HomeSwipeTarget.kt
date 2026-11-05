package org.arkikeskus.launcher.feature.home

/** What a vertical swipe up on home drives ([swipeUpTarget]). */
internal enum class SwipeUpTarget { FOLDER_APP, DRAWER, NONE }

/**
 * Issue #17 (opt-in): a swipe up that starts on a home folder icon ([folderUnderFinger]) opens the folder's
 * first app while [folderSwipeOpensFirstApp] is on -- whether or not the drawer swipe is -- and otherwise
 * drives the drawer like a swipe from anywhere on home ([swipeUpForDrawer]), or nothing.
 */
internal fun swipeUpTarget(folderUnderFinger: Boolean, folderSwipeOpensFirstApp: Boolean, swipeUpForDrawer: Boolean): SwipeUpTarget = when {
    folderUnderFinger && folderSwipeOpensFirstApp -> SwipeUpTarget.FOLDER_APP
    swipeUpForDrawer -> SwipeUpTarget.DRAWER
    else -> SwipeUpTarget.NONE
}

/**
 * Gesture navigation: a touch that starts inside the system's bottom gesture zone -- the mandatory
 * system-gesture inset, [zonePx] high at the bottom of a [height]-px surface -- is the system's swipe
 * up (recent apps, home), never one of ours. The system takes the pointer over a moment later with a
 * cancel, but on some phones not before our own slop has fired and opened the drawer, or a folder's
 * first app, underneath the overview (a Telegram report on 1.0.1).
 */
internal fun startsInBottomGestureZone(y: Float, height: Int, zonePx: Float): Boolean =
    zonePx > 0f && y >= height - zonePx
