package org.arkikeskus.launcher.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.After
import org.junit.Test

/** What the theme root is told: nothing until the stored settings are read, then the stored choice. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelTest {
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state.asStateFlow()
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun theChoiceIsUnknownUntilTheSettingsAreReadThenTheStoredOne() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repository = SettingsRepository(MemoryStore())
        repository.setBaseColour(LauncherSettings.BASE_COLOUR_TEAL)
        repository.setFontFamily(LauncherSettings.FONT_SERIF)
        val viewModel = ThemeViewModel(repository)
        assertThat(viewModel.theme.value).isNull()

        val collector = launch { viewModel.theme.collect {} }
        advanceUntilIdle()
        assertThat(viewModel.theme.value)
            .isEqualTo(ThemeChoice(LauncherSettings.FONT_SERIF, LauncherSettings.BASE_COLOUR_TEAL, null))
        collector.cancel()
    }

    @Test fun theChameleonsColourIsKeptForTheNextStart() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = MemoryStore()
        val viewModel = ThemeViewModel(SettingsRepository(store))
        val collector = launch { viewModel.theme.collect {} }
        viewModel.rememberChameleon(0xFF1F8EF1.toInt())
        advanceUntilIdle()
        assertThat(viewModel.theme.value!!.chameleonLastArgb).isEqualTo(0xFF1F8EF1.toInt())
        collector.cancel()

        // A new process reads it back before anything is drawn.
        val next = ThemeViewModel(SettingsRepository(store))
        val nextCollector = launch { next.theme.collect {} }
        advanceUntilIdle()
        assertThat(next.theme.value!!.chameleonLastArgb).isEqualTo(0xFF1F8EF1.toInt())
        nextCollector.cancel()
    }
}
