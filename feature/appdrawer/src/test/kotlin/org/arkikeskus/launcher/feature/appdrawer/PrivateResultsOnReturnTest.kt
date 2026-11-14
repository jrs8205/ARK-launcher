package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import org.arkikeskus.launcher.data.ProfileKind
import org.arkikeskus.launcher.data.ProfileSnapshot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The launcher leaves the screen with a search's private matches on it and comes back after the space has
 * locked. The screen stops reading the state while it is away, and what it is handed first on the return is
 * what was kept from before: nothing of the space may be drawn from that, whatever the search that answers
 * again still waits for. The real screen over the real view model, with a lifecycle the test moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PrivateResultsOnReturnTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()
    private val context: Application = RuntimeEnvironment.getApplication()
    private val privateUser: UserHandle = UserHandle.getUserHandleForUid(12 * 100_000)
    private val own = ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)
    private val space = ProfileSnapshot(12, ProfileKind.Private, badged = false, paused = false)

    private lateinit var wiring: DrawerWiring
    private lateinit var viewModel: AppDrawerViewModel

    @Before fun setUp() {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 12, "Private", 0)
        // Before the wiring: its app list is queried eagerly. A fake app's label resolves to its package name.
        installFakeApp(context, "com.mail", "Mail")
        installFakeApp(context, "com.vault", "Vault", privateUser)
        wiring = DrawerWiring(context, TestScope(), listOf(own, space))
        viewModel = wiring.viewModel()
    }

    @After fun tearDown() {
        wiring.clear(viewModel)
        wiring.close()
    }

    /** How many times the private app's name is on screen. */
    private fun privateNamesOnScreen(): Int =
        compose.onAllNodesWithText("com.vault", useUnmergedTree = true).fetchSemanticsNodes().size

    /** The stores and the search answer from their own threads, the search after 200 ms of the looper's clock. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.waitForIdle()
        }
    }

    @Test fun theDrawerThatReturnsDrawsNothingOfASpaceThatLockedWhileItWasAway() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AppDrawerScreen(onClose = {}, onOpenSettings = {}, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
        await("the search field") { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("vault")
        await("the private match") { privateNamesOnScreen() == 1 }

        // Off the screen for longer than the state goes on being followed, and the space locks meanwhile.
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        viewModel.setPrivateLocked(true)
        await("the lock") { wiring.privateSpaceRepository.state.value?.locked == true }
        // The search that answers again on the return is held back: only what was kept can be drawn.
        val gate = CompletableDeferred<Unit>()
        wiring.searchGate = gate

        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.waitForIdle()

        assertThat(privateNamesOnScreen()).isEqualTo(0)
        assertThat(viewModel.uiState.value.query).isEqualTo("vault")
        assertThat(gate.isCompleted).isFalse()
        gate.complete(Unit)
    }
}
