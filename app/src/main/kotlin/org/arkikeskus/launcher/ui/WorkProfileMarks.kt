package org.arkikeskus.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.arkikeskus.launcher.data.WorkProfile
import org.arkikeskus.launcher.ui.component.LocalProfileMarks
import org.arkikeskus.launcher.ui.component.profileMarks
import org.arkikeskus.launcher.feature.appdrawer.R as DrawerR

/**
 * Every app icon under [content] says a work app's profile to a screen reader, on each surface of the launcher
 * window (home, dock, folders, drawer, search): the work profile's serial goes to
 * [org.arkikeskus.launcher.ui.component.LocalProfileMarks] with the name its drawer tab has.
 */
@Composable
internal fun WorkProfileMarks(workProfile: StateFlow<WorkProfile?>, content: @Composable () -> Unit) {
    val serial = workProfile.collectAsStateWithLifecycle().value?.userSerial
    val name = stringResource(DrawerR.string.drawer_tab_work)
    val marks = remember(serial, name) { profileMarks(serial, name) }
    CompositionLocalProvider(LocalProfileMarks provides marks, content = content)
}
