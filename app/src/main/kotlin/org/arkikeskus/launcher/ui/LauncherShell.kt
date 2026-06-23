package org.arkikeskus.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import org.arkikeskus.launcher.feature.appdrawer.AppDrawerScreen
import org.arkikeskus.launcher.feature.home.HomeScreen

/**
 * Hosts the launcher surface: the home screen with the app drawer as a slide-up overlay.
 * Both BACK and HOME (via [homeSignals]) close the drawer. [onOpenSettings] is triggered by a
 * long-press on the home screen and by tapping the launcher's own icon in the drawer.
 */
@Composable
fun LauncherShell(
    homeSignals: Flow<Unit>,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var drawerOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(homeSignals) {
        homeSignals.collect { drawerOpen = false }
    }

    HomeScreen(
        onOpenDrawer = { drawerOpen = true },
        onOpenSettings = onOpenSettings,
        modifier = modifier.fillMaxSize(),
    )

    AnimatedVisibility(
        visible = drawerOpen,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        AppDrawerScreen(
            onClose = { drawerOpen = false },
            onOpenSettings = {
                drawerOpen = false
                onOpenSettings()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    BackHandler(enabled = drawerOpen) { drawerOpen = false }
}
