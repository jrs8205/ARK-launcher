package org.arkikeskus.launcher.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.arkikeskus.launcher.data.LauncherAppsSource
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.IconEpochs
import javax.inject.Inject

/** The app-icon style (icon pack, themed, the icon-size multiplier), so the shell's floating drag icon
 *  matches the surfaces. */
/** ARK's own status bar is on (it backs the system bar with a dark scrim); the system bar is hidden on home. */
data class StatusBarSettings(val ownBarShown: Boolean = false, val systemBarHidden: Boolean = false)

data class IconStyle(val iconPack: String = "", val themed: Boolean = false, val iconScale: Float = 1f)

/**
 * Exposes just the icon-style settings to [LauncherShell] so the single floating drag icon — drawn
 * above the home and drawer, outside their own CompositionLocal scopes — renders with the selected
 * icon pack / themed icons instead of the default system icon. Also relays the icon re-fetch epochs
 * (see [org.arkikeskus.launcher.model.IconEpochs]) that the shell provides to every icon surface.
 */
@HiltViewModel
class LauncherShellViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    launcherAppsSource: LauncherAppsSource,
) : ViewModel() {
    val iconStyle: StateFlow<IconStyle> = settingsRepository.settings
        .map { IconStyle(it.iconPackPackage, it.useThemedIcons, it.iconScale) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IconStyle())

    val iconEpochs: StateFlow<IconEpochs> = launcherAppsSource.iconEpochs

    /** The status-bar settings the system bars' glyph colour depends on (see SystemBarContrast). */
    val statusBars: StateFlow<StatusBarSettings> = settingsRepository.settings
        .map { StatusBarSettings(ownBarShown = it.showStatusBar, systemBarHidden = it.hideSystemStatusBar) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusBarSettings())

    /** The long-press delay setting, applied once at the shell root for every press site under it. */
    val longPressScale: StateFlow<Float> = settingsRepository.settings
        .map { it.longPressScale }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)
}
