package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Tells [onOpened] when the drawer opens, and again every time the launcher comes back to the front while the
 * drawer is open. The drawer stays open behind another screen (Android's settings, an app started from it), so
 * "it opened" alone misses what was changed there: Android's "hide private space when locked" has no broadcast.
 */
@Composable
internal fun DrawerOpenedEffect(drawerOpen: Boolean, onOpened: () -> Unit) {
    val opened by rememberUpdatedState(onOpened)
    // Runs when the screen is at the front, and again whenever it returns there or [drawerOpen] changes.
    LifecycleResumeEffect(drawerOpen) {
        if (drawerOpen) opened()
        onPauseOrDispose { }
    }
}
