package org.arkikeskus.launcher.feature.screensaver.dream

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.arkikeskus.launcher.data.ActiveNotification
import org.arkikeskus.launcher.ui.LocalBackgroundDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The running screensaver's state, collected from what the launcher gives it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DreamStateCollectorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun unread(key: String, app: String) = ActiveNotification(
        key = key,
        packageName = app,
        postTime = 1L,
        isOngoing = false,
        isGroupSummary = false,
        isClearable = true,
        smallIcon = null,
    )

    /** A change shows a frame later, and what an effect then does (the pulse) a frame after that. */
    private fun frames() = repeat(3) { compose.mainClock.advanceTimeByFrame() }

    /** Starts the collector and answers with a reader of its latest state. */
    private fun collect(
        notifications: MutableStateFlow<List<ActiveNotification>>,
        onChargeState: (DreamChargeState) -> Unit = {},
    ): () -> DreamUiState {
        var state: DreamUiState? = null
        // The clock ticks for as long as the screensaver is up: the test moves time itself.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // The row's icons are read at once, on this thread: read on another one they come back on it
            // under the rule the tests had then, and the screen is then worked on from two threads at once.
            CompositionLocalProvider(LocalBackgroundDispatcher provides Dispatchers.Unconfined) {
                val collected = rememberDreamUiState(LocalContext.current, DreamOptions(), notifications, onChargeState) {}
                state = collected
                DreamScreen(state = collected, options = DreamOptions(), landscape = false, onTap = {})
            }
        }
        frames()
        return { compose.runOnIdle { state!! } }
    }

    @Test
    fun `it draws with an empty row when the listener never connected`() {
        // A cold process Android bound for the screensaver alone: the listener's list is still empty.
        val state = collect(MutableStateFlow(emptyList()))

        assertThat(state().notificationGroups).isEmpty()
        assertThat(state().notificationPulseToken).isEqualTo(0)
    }

    @Test
    fun `a notification that arrives while it is up joins the row and pulses it`() {
        val notifications = MutableStateFlow(listOf(unread("a", "app.one")))
        val state = collect(notifications)
        // What waited at the start shows quietly.
        assertThat(state().notificationGroups.map { it.packageName }).containsExactly("app.one")
        assertThat(state().notificationPulseToken).isEqualTo(0)

        notifications.value = listOf(unread("a", "app.one"), unread("b", "app.two"))
        frames()

        assertThat(state().notificationGroups.map { it.packageName }).containsExactly("app.one", "app.two")
        assertThat(state().notificationPulseToken).isEqualTo(1)
    }

    @Test
    fun `the row empties when the list of the listener does`() {
        val notifications = MutableStateFlow(listOf(unread("a", "app.one")))
        val state = collect(notifications)

        notifications.value = emptyList()
        frames()

        assertThat(state().notificationGroups).isEmpty()
    }

    @Test
    fun `the state of the battery reaches the caller`() {
        RuntimeEnvironment.getApplication().sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 100)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC),
        )
        val heard = mutableListOf<DreamChargeState>()

        collect(MutableStateFlow(emptyList())) { heard += it }()

        assertThat(heard).contains(DreamChargeState.FULL)
    }
}
