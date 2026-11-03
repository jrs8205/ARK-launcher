package org.arkikeskus.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject

/** The settings the theme itself depends on: the user's font (Settings > Icons > Font). */
@HiltViewModel
class ThemeViewModel @Inject constructor(settingsRepository: SettingsRepository) : ViewModel() {
    val fontFamily: StateFlow<String> = settingsRepository.settings
        .map { it.fontFamily }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherSettings.FONT_DEFAULT)
}

/** [LauncherTheme] with the user's font, for every activity of the launcher. */
@Composable
fun LauncherAppTheme(content: @Composable () -> Unit) {
    val font by hiltViewModel<ThemeViewModel>().fontFamily.collectAsStateWithLifecycle()
    LauncherTheme(fontFamily = launcherFontFamily(font), content = content)
}
