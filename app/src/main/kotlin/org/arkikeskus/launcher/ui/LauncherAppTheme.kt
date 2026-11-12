package org.arkikeskus.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.expressive.paletteSeed
import javax.inject.Inject

/**
 * The settings the theme itself depends on: the user's font (Settings > Icons > Font), the base
 * colour (Settings > Appearance) and, for the Chameleon, the wallpaper colour it last followed.
 */
data class ThemeChoice(val fontFamily: String, val baseColour: String, val chameleonLastArgb: Int?)

@HiltViewModel
class ThemeViewModel @Inject constructor(private val settingsRepository: SettingsRepository) : ViewModel() {
    /** Null until the stored settings have been read: the theme root draws nothing before that, so
     *  no frame shows the default colour to someone who chose another. */
    val theme: StateFlow<ThemeChoice?> = settingsRepository.settings
        .map { ThemeChoice(it.fontFamily, it.baseColour, it.chameleonLastArgb) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Keeps the wallpaper colour the Chameleon follows now, for the next start's first frame. */
    fun rememberChameleon(argb: Int?) {
        viewModelScope.launch { settingsRepository.setChameleonLastArgb(argb) }
    }
}

/** [LauncherTheme] in the user's base colour and font, for every activity of the launcher. */
@Composable
fun LauncherAppTheme(content: @Composable () -> Unit) {
    val viewModel = hiltViewModel<ThemeViewModel>()
    val choice by viewModel.theme.collectAsStateWithLifecycle()
    LauncherAppTheme(choice, onChameleonColour = viewModel::rememberChameleon, content = content)
}

/**
 * The theme for a stored [choice]; nothing is composed while it is still null. A fixed colour never
 * asks for the wallpaper. The Chameleon starts from the colour it last followed ([wallpaperArgb] is
 * handed it as the value to show until Android answers) and hands a new one to [onChameleonColour].
 */
@Composable
internal fun LauncherAppTheme(
    choice: ThemeChoice?,
    onChameleonColour: (Int?) -> Unit,
    wallpaperArgb: @Composable (initial: Int?) -> Int? = { rememberWallpaperArgb(it) },
    content: @Composable () -> Unit,
) {
    if (choice == null) return
    val chameleon = choice.baseColour == LauncherSettings.BASE_COLOUR_CHAMELEON
    val wallpaper = if (chameleon) wallpaperArgb(choice.chameleonLastArgb) else null
    if (chameleon) {
        LaunchedEffect(wallpaper) {
            if (wallpaper != choice.chameleonLastArgb) onChameleonColour(wallpaper)
        }
    }
    val dark = isSystemInDarkTheme()
    val palette = remember(choice.baseColour, wallpaper, dark) {
        basePalette(paletteSeed(choice.baseColour, wallpaper), dark)
    }
    LauncherTheme(palette = palette, fontFamily = launcherFontFamily(choice.fontFamily), content = content)
}
