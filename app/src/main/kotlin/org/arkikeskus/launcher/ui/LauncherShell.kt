package org.arkikeskus.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.arkikeskus.launcher.feature.appdrawer.AppDrawerScreen
import org.arkikeskus.launcher.feature.home.HomeScreen

/**
 * Hosts the launcher surface: the home screen with the app drawer as a slide-up overlay.
 * Keeps the drawer open/close state; back closes the drawer.
 */
@Composable
fun LauncherShell(modifier: Modifier = Modifier) {
    var drawerOpen by rememberSaveable { mutableStateOf(false) }

    HomeScreen(
        onOpenDrawer = { drawerOpen = true },
        modifier = modifier.fillMaxSize(),
    )

    AnimatedVisibility(
        visible = drawerOpen,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        AppDrawerScreen(
            onClose = { drawerOpen = false },
            modifier = Modifier.fillMaxSize(),
        )
    }

    BackHandler(enabled = drawerOpen) { drawerOpen = false }
}
