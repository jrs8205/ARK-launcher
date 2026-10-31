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
