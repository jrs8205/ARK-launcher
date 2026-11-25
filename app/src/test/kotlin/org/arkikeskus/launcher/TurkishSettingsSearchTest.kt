package org.arkikeskus.launcher

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.search.SettingItem
import org.arkikeskus.launcher.data.search.SettingsSearchProvider
import org.arkikeskus.launcher.data.search.matchSettings
import org.arkikeskus.launcher.model.SearchResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "tr")
class TurkishSettingsSearchTest {
    @Test fun turkishKeyboardCapitalsFindTheSameSettings() = runTest {
        val provider = SettingsSearchProvider(RuntimeEnvironment.getApplication())
        for (query in listOf("internet", "İnternet")) {
            val keys = provider.query(query).filterIsInstance<SearchResult.Setting>().map { it.key }
            assertThat(keys).containsExactly("wifi", "network")
        }
        val light = SettingItem("light", "", "ışık", emptyList())
        assertThat(matchSettings(listOf(light), "Işık")).hasSize(1)
    }
}
