package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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

/**
 * Issue #33, on a phone with a second tab (a work profile or the private space): with the search field at the
 * bottom the tab row still sits OVER the apps' grid, as it does with the field at the top. The two were laid
 * out on top of each other: the grid began where the tab row did, covered it, and took the taps meant for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerBottomSearchTabsTest {
    @get:Rule val compose = createComposeRule()

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

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun showWithTheSearchField(atBottom: Boolean) {
        runBlocking { wiring.settingsRepository.setDrawerSearchAtBottom(atBottom) }
        compose.setContent { AppDrawerScreen(onClose = {}, onOpenSettings = {}, viewModel = viewModel) }
        await("the tabs and the apps") {
            val state = viewModel.uiState.value
            state.searchAtBottom == atBottom && DrawerTab.Private in state.tabs && state.apps.any { it.packageName == "com.mail" }
        }
        await("the grid") { compose.onAllNodesWithText("com.mail").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tabRowBottom() = compose.onNodeWithText("Private").fetchSemanticsNode().boundsInRoot.bottom

    private fun firstAppTop() = compose.onAllNodesWithText("com.mail").fetchSemanticsNodes().minOf { it.boundsInRoot.top }

    @Test fun withTheSearchFieldAtTheBottomTheTabRowIsStillOverTheGrid() {
        showWithTheSearchField(atBottom = true)

        assertThat(firstAppTop()).isAtLeast(tabRowBottom())
        // And the field is under the grid, where it was asked to be.
        val field = compose.onNodeWithTag(DRAWER_SEARCH_FIELD_TAG).fetchSemanticsNode().boundsInRoot
        assertThat(field.top).isAtLeast(firstAppTop())
    }

    /** As it always was with the field at the top: the field, the tab row, then the grid. */
    @Test fun withTheSearchFieldAtTheTopTheTabRowIsOverTheGrid() {
        showWithTheSearchField(atBottom = false)

        assertThat(firstAppTop()).isAtLeast(tabRowBottom())
        val field = compose.onNodeWithTag(DRAWER_SEARCH_FIELD_TAG).fetchSemanticsNode().boundsInRoot
        assertThat(field.bottom).isAtMost(tabRowBottom())
    }
}
