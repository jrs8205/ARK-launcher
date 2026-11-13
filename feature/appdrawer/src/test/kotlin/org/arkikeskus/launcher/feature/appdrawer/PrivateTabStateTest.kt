package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.arkikeskus.launcher.data.ProfileKind
import org.arkikeskus.launcher.data.ProfileSnapshot
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When the drawer has a Private tab, from the real view model over the real repositories: only on a phone
 * with a private space, and not while the space is locked and its entry is to stay hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PrivateTabStateTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val own = ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)
    private fun space(locked: Boolean) = ProfileSnapshot(12, ProfileKind.Private, badged = false, paused = locked)

    private lateinit var wiring: DrawerWiring
    private lateinit var viewModel: AppDrawerViewModel
    private val collector = CoroutineScope(Dispatchers.Main.immediate)

    /** The drawer of a phone with [profiles] and one personal app; [before] runs before the view model exists. */
    private fun drawer(vararg profiles: ProfileSnapshot, before: suspend (DrawerWiring) -> Unit = {}) {
        installFakeApp(context, "com.mail", "Mail")
        wiring = DrawerWiring(context, TestScope(), profiles.toList())
        runBlocking { before(wiring) }
        viewModel = wiring.viewModel()
        collector.launch { viewModel.uiState.collect {} }
        // The stores answer from their own threads: the app list is there once they have.
        await("the app list") { it.apps.isNotEmpty() }
    }

    @After fun tearDown() {
        collector.cancel()
        wiring.clear(viewModel)
        wiring.close()
    }

    private fun idle(rounds: Int = 1) = repeat(rounds) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(what: String, condition: (AppDrawerUiState) -> Boolean): AppDrawerUiState {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition(viewModel.uiState.value)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what: tabs = ${viewModel.uiState.value.tabs}" }
            idle()
        }
        return viewModel.uiState.value
    }

    @Test fun aPhoneWithoutAPrivateSpaceHasOnlyThePersonalTab() {
        drawer(own)
        idle(rounds = 10)

        assertThat(viewModel.uiState.value.tabs).containsExactly(DrawerTab.Personal)
        assertThat(viewModel.uiState.value.privateSpace).isNull()
    }

    @Test fun aLockedPrivateSpaceHasItsTabAndItsPageIsTheLockedOne() {
        drawer(own, space(locked = true))

        val state = await("the Private tab") { DrawerTab.Private in it.tabs }

        assertThat(state.tabs).containsExactly(DrawerTab.Personal, DrawerTab.Private).inOrder()
        assertThat(state.privateSpace).isEqualTo(PrivateSpaceUi(locked = true))
        assertThat(state.privateApps).isEmpty()
    }

    @Test fun withHidingOnALockedSpaceHasNoTab() {
        drawer(own, space(locked = true)) { it.settingsRepository.setHidePrivateSpaceWhenLocked(true) }
        idle(rounds = 25)

        assertThat(viewModel.uiState.value.tabs).containsExactly(DrawerTab.Personal)
        assertThat(viewModel.uiState.value.privateSpace).isNull()
    }

    @Test fun anUnlockedSpaceHasItsTabEvenWithHidingOn() {
        drawer(own, space(locked = false)) { it.settingsRepository.setHidePrivateSpaceWhenLocked(true) }

        val state = await("the Private tab") { DrawerTab.Private in it.tabs }

        assertThat(state.privateSpace).isEqualTo(PrivateSpaceUi(locked = false))
    }
}
