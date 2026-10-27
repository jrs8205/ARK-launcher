package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * What the drawer leaves behind when it goes: HOME takes its windows (a folder's sheet, the Edit
 * dialog) with it, and ANY way of closing it — a pull-down, Back from an app — clears the query (and
 * the search focus with it, so the keyboard never stays up over the home screen) so the drawer
 * reopens fresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppDrawerScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val testScope = TestScope()
    private val wiring = DrawerWiring(context, testScope)
    private val viewModel = wiring.viewModel()
    private val homeSignals = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val drawerOpen = mutableStateOf(true)

    @After fun tearDown() {
        wiring.clear(viewModel)
        wiring.close()
    }

    private fun show() {
        compose.setContent {
            AppDrawerScreen(
                onClose = {},
                onOpenSettings = {},
                homeSignals = homeSignals,
                drawerOpen = drawerOpen.value,
                viewModel = viewModel,
            )
        }
        settle()
    }

    private fun settle() {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun addApps() = context.getString(R.string.folder_add_apps)

    @Test fun homeTakesTheFolderSheetWithTheDrawer() {
        runBlocking { wiring.settingsRepository.createDrawerFolder("Tools") }
        show()
        compose.onNodeWithText("Tools").performClick()
        settle()
        compose.onNodeWithText(addApps()).assertExists()

        compose.runOnIdle { homeSignals.tryEmit(true) }
        settle()

        compose.onNodeWithText(addApps()).assertDoesNotExist()
    }

    /** The first letter of a search used to flash "No results": the query reaches the screen at
     *  once while the results (debounced, then searched) still answer the previous query. */
    @Test fun noResultsWaitsForTheResults() {
        show()
        val noResults = context.getString(R.string.search_no_results)
        compose.onNode(hasSetTextAction()).performTextInput("c")
        settle() // the query is on screen; the looper's clock stands still, so the debounce has not run
        assertThat(viewModel.uiState.value.query).isEqualTo("c")
        compose.onNodeWithText(noResults, useUnmergedTree = true).assertDoesNotExist()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        settle()

        compose.onNodeWithText(noResults, useUnmergedTree = true).assertExists() // no app is called "c" on the test device
    }

    /** The focus goes the same way as on HOME (the keyboard with it); the query is what a test can see. */
    @Test fun closingTheDrawerDropsTheQuery() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("ch")
        settle()
        assertThat(viewModel.uiState.value.query).isEqualTo("ch")

        // Pulled down, or closed behind a launched app: no HOME press.
        drawerOpen.value = false
        settle()

        assertThat(viewModel.uiState.value.query).isEmpty()
    }
}
