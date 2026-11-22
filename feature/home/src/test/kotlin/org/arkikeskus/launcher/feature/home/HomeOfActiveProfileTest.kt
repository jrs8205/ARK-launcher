package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the home screen draws around a switch of the home profile: nothing of the profile that left, and
 * nothing it had open or remembered of it. The state the screen holds and the news of the switch arrive
 * apart, in either order, and the state can be an old one kept from before the screen was last shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomeOfActiveProfileTest {
    @get:Rule val compose = createComposeRule()

    private fun loaded(profile: Long) = HomeUiState(loaded = true, homeProfileId = profile)

    private val active = mutableStateOf<Long?>(1L)
    private val state = mutableStateOf(loaded(1))

    /** A home screen with something that can be opened, as a folder is. */
    private fun show() = compose.setContent {
        HomeOfActiveProfile(active = active.value, uiState = state.value) { _, shown ->
            var open by remember { mutableStateOf(false) }
            Column {
                Text(if (shown.loaded) "home of ${shown.homeProfileId}" else "placeholder")
                Text(if (open) "folder open" else "folder closed", Modifier.clickable { open = true })
            }
        }
    }

    /** The Activity's widget host, counting how often it is told to let go of its views. */
    private class CountingHost(context: android.content.Context) : LauncherWidgetHost(context, 1) {
        var forgotten = 0
        override fun forgetViews() {
            forgotten++
            super.forgetViews()
        }
    }

    /**
     * Review round 4. The widget views of a profile's home screen are registered with the Activity's host,
     * which outlives that screen. When the screen of a profile leaves, the host lets go of them (they
     * stayed in memory and went on receiving their widgets' updates); a change inside a profile lets go of
     * nothing, or its widgets would be made again for every change.
     */
    @Test fun theHostLetsGoOfAProfilesWidgetViewsWhenItsScreenLeaves() {
        val host = CountingHost(org.robolectric.RuntimeEnvironment.getApplication())
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalAppWidgetHost provides host) {
                HomeOfActiveProfile(active = active.value, uiState = state.value) { _, shown -> Text("home of ${shown.homeProfileId}") }
            }
        }
        compose.onNodeWithText("home of 1").assertIsDisplayed()
        assertThat(host.forgotten).isEqualTo(0)

        state.value = loaded(1).copy(pageCount = 3)
        compose.waitForIdle()
        assertThat(host.forgotten).isEqualTo(0)

        // The switch is known before the new profile's state has come: the old screen has left already.
        active.value = 2
        compose.waitForIdle()
        assertThat(host.forgotten).isEqualTo(1)

        state.value = loaded(2)
        compose.onNodeWithText("home of 2").assertIsDisplayed()
        assertThat(host.forgotten).isEqualTo(1)
    }

    @Test fun whatIsOpenDoesNotSurviveASwitch() {
        show()
        compose.onNodeWithText("folder closed").performClick()
        compose.onNodeWithText("folder open").assertIsDisplayed()

        active.value = 2
        state.value = loaded(2)

        compose.onNodeWithText("home of 2").assertIsDisplayed()
        compose.onNodeWithText("folder closed").assertIsDisplayed()
    }

    @Test fun whatIsOpenStaysThroughAChangeInsideTheProfile() {
        show()
        compose.onNodeWithText("folder closed").performClick()

        state.value = loaded(1).copy(pageCount = 3)

        compose.onNodeWithText("folder open").assertIsDisplayed()
    }

    @Test fun aStateOfTheProfileThatLeftIsNotDrawn() {
        show()

        // The switch is known, and the state is still the one of the profile that left: an old state kept
        // from before the screen went behind Settings, or one the new rows have not reached yet.
        active.value = 2

        compose.onNodeWithText("home of 1").assertDoesNotExist()
        state.value = loaded(2)
        compose.onNodeWithText("home of 2").assertIsDisplayed()
    }

    @Test fun aStateThatIsAheadOfTheNewsOfTheSwitchIsNotDrawn() {
        show()

        state.value = loaded(2)

        compose.onNodeWithText("home of 2").assertDoesNotExist()
        compose.onNodeWithText("home of 1").assertDoesNotExist()
        active.value = 2
        compose.onNodeWithText("home of 2").assertIsDisplayed()
    }

    /** A start does not know its profile at once. Nothing is drawn for a guessed one: the screen of the first
     *  profile, shown for a moment where another is on, would start without what the real one had saved. */
    @Test fun nothingIsDrawnBeforeTheProfileIsKnown() {
        active.value = null
        state.value = HomeUiState()
        show()

        compose.onNodeWithText("placeholder").assertDoesNotExist()
        compose.onNodeWithText("folder closed").assertDoesNotExist()

        active.value = 1
        compose.onNodeWithText("placeholder").assertIsDisplayed()
    }

    /**
     * The process died while a widget was being added in the second profile (its id asked for, the system's
     * question still open) and the screen comes back from its saved state. What the screen starts with is
     * what it saved: a first moment as the first profile's screen saw nothing pending, and the start-up
     * sweep, which keeps only the pending widget's id, freed it under the question.
     */
    @Test fun aRestoredScreenNeverStartsWithoutWhatItsProfileSaved() {
        val startedWith = mutableListOf<String>()
        // What a process knows of the profile that is on: not saved, so a new process starts with [atStart].
        var atStart: Long? = 2
        lateinit var inProcess: MutableState<Long?>
        val tester = StateRestorationTester(compose)
        tester.setContent {
            val known = remember { mutableStateOf(atStart) }.also { inProcess = it }
            val profile = known.value
            HomeOfActiveProfile(active = profile, uiState = if (profile == null) HomeUiState() else loaded(profile)) { _, _ ->
                var pending by rememberSaveable { mutableStateOf("nothing pending") }
                // What a start acts on: the value its first composition has.
                remember { pending.also { startedWith += it } }
                Text(pending, Modifier.clickable { pending = "widget 7 pending" })
            }
        }
        compose.onNodeWithText("nothing pending").performClick()
        compose.onNodeWithText("widget 7 pending").assertIsDisplayed()
        startedWith.clear()

        // The new process: the saved state is there at once, the profile that is on is read a moment later.
        atStart = null
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("widget 7 pending").assertDoesNotExist()
        inProcess.value = 2
        compose.waitForIdle()

        compose.onNodeWithText("widget 7 pending").assertIsDisplayed()
        assertThat(startedWith).containsExactly("widget 7 pending")
    }

    @Test fun aStartKeepsWhatItHasWhenTheLayoutArrives() {
        // A start with the second profile on: first nothing is known, then which profile, then its layout.
        active.value = null
        state.value = HomeUiState()
        show()
        compose.onNodeWithText("placeholder").assertDoesNotExist()

        active.value = 2
        compose.onNodeWithText("placeholder").assertIsDisplayed()
        compose.onNodeWithText("folder closed").performClick()
        state.value = loaded(2)

        compose.onNodeWithText("home of 2").assertIsDisplayed()
        compose.onNodeWithText("folder open").assertIsDisplayed()
    }

    @Test fun aSwitchLandsOnTheNewProfilesStartPage() {
        val drag = HomeDragController()
        val pageRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        state.value = loaded(1).copy(pageCount = 4, homePage = 0)
        compose.setContent {
            HomeOfActiveProfile(active = active.value, uiState = state.value) { _, shown ->
                Workspace(
                    pageCount = shown.pageCount,
                    columns = 4,
                    rows = 6,
                    entries = emptyList(),
                    badges = emptyMap(),
                    badgeShowCount = false,
                    badgeScale = 1f,
                    showLabels = true,
                    showPageIndicator = true,
                    locked = false,
                    homeSignals = emptyFlow(),
                    homePage = shown.homePage,
                    pageRequests = pageRequests,
                    dragController = drag,
                    widgetDragController = remember { WidgetDragController(drag) },
                    onAddWidget = { _, _ -> },
                    onAppClick = {},
                    onAppMenu = { _, _, _ -> },
                    onMove = { _, _, _, _ -> true },
                    onMoveFolder = { _, _, _, _ -> true },
                    onDropOnDock = { _, _ -> },
                    onRemoveFromHome = {},
                    onOpenFolder = { _, _ -> },
                    onLaunchShortcut = {},
                    onRemoveShortcut = {},
                    onCreateFolder = { _, _ -> },
                    onAddToFolder = { _, _ -> },
                    onEmptyAreaMenu = { _, _ -> },
                    modifier = Modifier.size(300.dp, 500.dp),
                )
            }
        }
        // The user is on the fourth page of the first profile.
        compose.runOnIdle { pageRequests.tryEmit(3) }
        compose.waitForIdle()
        assertThat(drag.currentPage).isEqualTo(3)

        active.value = 2
        state.value = loaded(2).copy(pageCount = 4, homePage = 1)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(1)
    }
}
