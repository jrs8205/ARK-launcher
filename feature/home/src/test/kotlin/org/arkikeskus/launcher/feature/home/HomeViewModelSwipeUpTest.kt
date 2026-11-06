package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Looper
import android.os.Process
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The "Swipe up opens" choices reach the home screen resolved: the icon's key to the app it opens,
 * only while that app is still installed — an uninstalled choice leaves the icon with the usual
 * swipe instead of a gesture that opens nothing. Edit saves and clears a choice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeViewModelSwipeUpTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val user = Process.myUserHandle()
    private fun app(name: String) = AppItem("com.$name", "Main", user, 0L, name)

    @Test fun choicesResolveToInstalledAppsOnly() = runTest {
        installFakeApp(context, "com.mail", "Mail", className = "Main")
        installFakeApp(context, "com.maps", "Maps", className = "Main")
        val wiring = ProductionWiring(context, this)
        try {
            wiring.settingsRepository.setSwipeUpApp(app("mail").key, app("maps").key)
            wiring.settingsRepository.setSwipeUpApp(app("maps").key, app("gone").key)
            val viewModel = wiring.homeViewModel()

            val state = awaitState(viewModel) { it.loaded && it.swipeUpTargets.isNotEmpty() }

            assertThat(state.swipeUpTargets.keys).containsExactly(app("mail").key)
            assertThat(state.swipeUpTargets[app("mail").key]?.packageName).isEqualTo("com.maps")

            // Edit's picks are saved on the ViewModel's scope: wait for each write to land (the exact
            // map, not a partial condition a read in between could satisfy).
            viewModel.setSwipeUpApp(app("mail"), null)
            awaitChoices(wiring) { it == mapOf(app("maps").key to app("gone").key) }
            viewModel.setSwipeUpApp(app("maps"), app("mail"))
            awaitChoices(wiring) { it == mapOf(app("maps").key to app("mail").key) }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    private suspend fun awaitState(viewModel: HomeViewModel, done: (HomeUiState) -> Boolean): HomeUiState {
        var state = viewModel.uiState.value
        awaitUntil("the state") { state = viewModel.uiState.first(); done(state) }
        return state
    }

    private suspend fun awaitChoices(wiring: ProductionWiring, done: (Map<String, String>) -> Boolean): Map<String, String> {
        var choices = emptyMap<String, String>()
        awaitUntil("the saved choices") { choices = wiring.settingsRepository.swipeUpApps.first(); done(choices) }
        return choices
    }

    /** Lets the collectors run through their storage reads and main-looper hops. */
    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
