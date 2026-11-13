package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
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
import java.time.Duration
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

    /** Lets the other threads answer and the main looper's own clock move on (the search waits 200 ms of it). */
    private fun idle(rounds: Int = 1) = repeat(rounds) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
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

    // The search.

    private val privateUser: UserHandle = UserHandle.getUserHandleForUid(12 * 100_000)

    /** The private profile is one the user manager answers for, with one app of its own, "com.vault". */
    private fun aPrivateApp() {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 12, "Private", 0)
        installFakeApp(context, "com.vault", "Vault", privateUser)
    }

    private fun search(text: String): AppDrawerUiState {
        viewModel.onQueryChange(text)
        return await("the answer to '$text'") { it.query == text && !it.searchPending }
    }

    @Test fun aSearchFindsAPrivateAppOnlyWhileTheSpaceIsUnlocked() {
        aPrivateApp()
        drawer(own, space(locked = false))
        await("the private app") { it.privateApps.isNotEmpty() }

        val found = search("vault")
        assertThat(found.privateResults.map { it.packageName }).containsExactly("com.vault")
        // Never among the other apps: those are started, dragged and counted the general way.
        assertThat(found.apps.map { it.packageName }).doesNotContain("com.vault")

        viewModel.setPrivateLocked(true)
        await("the lock") { it.privateSpace?.locked == true }
        val locked = search("vaul")
        assertThat(locked.privateResults).isEmpty()
        assertThat(locked.apps.map { it.packageName }).doesNotContain("com.vault")
    }

    @Test fun resultsOnScreenLoseThePrivateAppsTheMomentTheSpaceLocks() {
        aPrivateApp()
        drawer(own, space(locked = false))
        await("the private app") { it.privateApps.isNotEmpty() }
        assertThat(search("vault").privateResults.map { it.packageName }).containsExactly("com.vault")

        // The space locks while the results are on screen; nothing is typed.
        viewModel.setPrivateLocked(true)

        val state = await("the results without the private app") { it.privateSpace?.locked == true && it.privateResults.isEmpty() }
        assertThat(state.query).isEqualTo("vault")
        // And they come back when it is unlocked again, the query still standing.
        viewModel.setPrivateLocked(false)
        await("the private app found again") { it.privateResults.isNotEmpty() }
    }

    @Test fun theSpacesRowOnScreenGoesWhenAHiddenSpaceLocks() {
        drawer(own, space(locked = false)) { it.settingsRepository.setHidePrivateSpaceWhenLocked(true) }
        await("the Private tab") { DrawerTab.Private in it.tabs }
        // Unlocked, the first letters of the name are enough.
        assertThat(search("private sp").privateSpaceEntry).isTrue()

        viewModel.setPrivateLocked(true)

        // Locked and hidden, they are not: the row leaves the results that are on screen.
        val state = await("the row gone") { DrawerTab.Private !in it.tabs && !it.privateSpaceEntry }
        assertThat(state.query).isEqualTo("private sp")
    }

    @Test fun theSpacesOwnRowShowsItsTab() {
        drawer(own, space(locked = false))
        await("the Private tab") { DrawerTab.Private in it.tabs }
        assertThat(search("mail").privateSpaceEntry).isFalse()
        assertThat(search("private sp").privateSpaceEntry).isTrue()

        viewModel.openPrivateSpace()

        val state = await("the Private tab chosen") { it.query.isEmpty() && it.selectedTab == DrawerTab.Private }
        assertThat(state.privateSpaceEntry).isFalse()
        assertThat(wiring.quietModeRequests).isEmpty() // it was unlocked already
    }

    @Test fun aHiddenLockedSpaceIsReachedByItsWholeNameAndUnlocked() {
        drawer(own, space(locked = true)) { it.settingsRepository.setHidePrivateSpaceWhenLocked(true) }
        idle(rounds = 25)
        assertThat(viewModel.uiState.value.tabs).containsExactly(DrawerTab.Personal)
        // Its first letters give nothing away; the whole name is the way in.
        assertThat(search("private").privateSpaceEntry).isFalse()
        assertThat(search("private space").privateSpaceEntry).isTrue()

        viewModel.openPrivateSpace()

        // Android is asked to unlock; once it has, the tab exists and is the one shown.
        val state = await("the Private tab") { DrawerTab.Private in it.tabs && it.selectedTab == DrawerTab.Private }
        assertThat(wiring.quietModeRequests).containsExactly(12L to false)
        assertThat(state.privateSpace?.locked).isFalse()
        assertThat(state.query).isEmpty()
    }

    @Test fun aWishForThePrivateTabIsGivenUpWhenTheDrawerCloses() {
        drawer(own, space(locked = true)) { it.settingsRepository.setHidePrivateSpaceWhenLocked(true) }
        idle(rounds = 25)
        // The unlock request is refused here (as when the user cancels Android's prompt): the wish stays open.
        wiring.refuseQuietMode = true
        search("private space")
        viewModel.openPrivateSpace()
        idle(rounds = 10)
        assertThat(viewModel.uiState.value.tabs).containsExactly(DrawerTab.Personal)

        viewModel.onDrawerClosed()
        // Unlocked later, some other way: the drawer does not jump to the tab on its own.
        wiring.refuseQuietMode = false
        viewModel.setPrivateLocked(false)
        val state = await("the Private tab") { DrawerTab.Private in it.tabs }
        idle(rounds = 10)
        assertThat(viewModel.uiState.value.selectedTab).isEqualTo(DrawerTab.Personal)
        assertThat(state.privateSpace?.locked).isFalse()
    }

    @Test fun anUnlockedSpaceHasItsTabEvenWithHidingOn() {
        drawer(own, space(locked = false)) { it.settingsRepository.setHidePrivateSpaceWhenLocked(true) }

        val state = await("the Private tab") { DrawerTab.Private in it.tabs }

        assertThat(state.privateSpace).isEqualTo(PrivateSpaceUi(locked = false))
    }
}
