package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.PendingWidgetIds
import org.arkikeskus.launcher.data.PinItem
import org.arkikeskus.launcher.data.PinPreview
import org.arkikeskus.launcher.data.PinRequest
import org.arkikeskus.launcher.data.PinRequestStore
import org.arkikeskus.launcher.data.WidgetBinder
import org.arkikeskus.launcher.data.WidgetPinRequest
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Review round 18. A widget another app asks to pin is confirmed for the home screen that is shown: the
 * profile that is on at the tap. When Android wants the user asked before it binds the widget, its dialog is
 * another app's window, and the user can leave it, switch profile in Settings and come back to allow. The
 * confirmation kept only the widget's id over that dialog, and the answer decided a place anew on whichever
 * profile was on by then: the widget said yes to on one home screen went to another.
 *
 * The profile of the tap is kept beside the id, in the saved state too, and the widget is stored only while
 * that profile is on. Otherwise nothing is added, and the id is given back.
 *
 * The real stores and the confirmation's real view model ([ProductionWiring]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PinRequestAcrossProfilesTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = storeScope.cancel()

    private object Resolver : AppRowResolver {
        override fun launchTargets(packageName: String, userSerial: Long) = error("unused")
        override fun missingShortcuts(packageName: String, userSerial: Long, shortcutIds: Collection<String>) = emptySet<String>()
        override fun isTemporarilyUnavailable(packageName: String, userSerial: Long) = false
        override fun ownUserSerial(): Long = 0L
        override fun pinShortcuts(packageName: String, userSerial: Long, shortcutIds: List<String>) = true
        override fun isPinnedHere(packageName: String, userSerial: Long, shortcutId: String) = true
    }

    private val clockProvider = ComponentName("com.clock", "com.clock.Analog")

    /** A fresh wrapper per call, as a recreated activity hands over. [onJudged] runs each time the request
     *  is judged against the home screen (the confirmation asks whether it is still valid as it does). */
    private fun clockRequest(onJudged: () -> Unit = {}) = object : WidgetPinRequest {
        override val item: PinItem = PinItem.Widget(clockProvider.flattenToString(), "Analog clock", ownProfile = true)
        override val info = AppWidgetProviderInfo().also { it.provider = clockProvider }
        override val isValid: Boolean get() = true.also { onJudged() }
        override fun accept(): Boolean = false
        override fun accept(appWidgetId: Int): Boolean = true
        override fun loadPreview(context: Context) = PinPreview("Clock", icon = null)
    }

    /** A conversation shortcut whose accept() the system grants. */
    private fun chatRequest(onJudged: () -> Unit = {}) = object : PinRequest {
        override val item: PinItem = PinItem.Shortcut("com.chat", "mia", userSerial = 0L, label = "Mia")
        override val isValid: Boolean get() = true.also { onJudged() }
        override fun accept(): Boolean = true
        override fun loadPreview(context: Context) = PinPreview("Chat", icon = null)
    }

    /** Hands out [id]; binds without the user being asked only when [bindsAtOnce]. */
    private class Binder(private val id: Int, private val bindsAtOnce: Boolean = false) : WidgetBinder {
        val deleted = ArrayList<Int>()
        override fun allocateId(): Int = id
        override fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean = bindsAtOnce
        override fun deleteId(appWidgetId: Int) { synchronized(deleted) { deleted += appWidgetId } }
    }

    /**
     * What a request's judging does in the tests of a switch that ends DURING a tap: nothing until [armed] is
     * set (right before the tap), then, once, another profile comes on. The tap judges the request against
     * the home screen before it goes to the store, so this is a switch in the middle of the tap's own look.
     */
    private fun switchingOnce(armed: AtomicBoolean, wiring: ProductionWiring, to: Long): () -> Unit = {
        if (armed.compareAndSet(true, false)) runBlocking { check(wiring.homeProfiles.switchTo(to)) }
    }

    private fun confirmation(wiring: ProductionWiring, saved: SavedStateHandle, binder: WidgetBinder) =
        PinRequestViewModel(
            context, wiring.settingsRepository, wiring.homeLayoutRepository,
            PinRequestStore(wiring.homeLayoutRepository, Resolver, storeScope), saved,
        ).also {
            it.binder = binder
            it.newItems = NewHomeItems()
            it.cellSize = HomeCellSize().apply { publish(80f, 100f, columns = 4, rows = 6) }
        }

    /** Runs the main looper (the ViewModel's scope) until [done], giving the IO work time. */
    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private suspend fun widgetsOf(wiring: ProductionWiring, profileId: Long) =
        wiring.rowsOfProfile(profileId).filter { it.isWidget }.map { it.appWidgetId }

    /** The user said "Add" with the first profile on, and Android's bind dialog is open for the id. */
    private suspend fun addedUpToTheBindDialog(vm: PinRequestViewModel) {
        vm.attach(clockRequest())
        awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }
        vm.add()
        awaitUntil("the bind dialog") { (vm.uiState.value as? PinRequestUiState.Confirm)?.bindRequest != null }
    }

    @Test fun aWidgetAllowedAfterAnotherProfileCameOnIsAddedToNoProfile() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val other = (wiring.homeProfiles.create("Other", copyOf = null) as HomeProfileRepository.Created.Done).profile
            val binder = Binder(4801)
            val vm = confirmation(wiring, SavedStateHandle(), binder)
            addedUpToTheBindDialog(vm)

            // With Android's dialog open: to Settings, another profile on, and back to allow.
            assertThat(whileTheMainLooperTurns("the switch") { wiring.homeProfiles.switchTo(other.id) }).isTrue()
            vm.onBindResult(allowed = true)
            awaitUntil("the confirmation to close") { vm.uiState.value is PinRequestUiState.Finished }

            assertThat(widgetsOf(wiring, other.id)).isEmpty()
            assertThat(widgetsOf(wiring, 1)).isEmpty()
            assertThat((vm.uiState.value as PinRequestUiState.Finished).failed).isTrue()
            // The id is given back: no row holds it, and it is bound to nothing the user sees.
            assertThat(binder.deleted).containsExactly(4801)
            assertThat(4801 in PendingWidgetIds.shared).isFalse()
        } finally {
            wiring.close()
        }
    }

    /** The profile of the tap is in the saved state beside the id: the process can die with Android's
     *  dialog open, and the answer then reaches a confirmation that was made anew. */
    @Test fun aWidgetAllowedAfterAnotherProfileCameOnIsNotAddedByARecreatedConfirmationEither() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val other = (wiring.homeProfiles.create("Other", copyOf = null) as HomeProfileRepository.Created.Done).profile
            val saved = SavedStateHandle()
            addedUpToTheBindDialog(confirmation(wiring, saved, Binder(4802)))

            // The process dies with the dialog open: memory is gone, the saved state is not.
            PendingWidgetIds.shared.remove(4802)
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            assertThat(whileTheMainLooperTurns("the switch") { wiring.homeProfiles.switchTo(other.id) }).isTrue()
            val binder = Binder(4802)
            val second = confirmation(wiring, restored, binder)
            awaitUntil("the id held again") { 4802 in PendingWidgetIds.shared }
            second.attach(clockRequest())
            awaitUntil("the confirmation") { second.uiState.value is PinRequestUiState.Confirm }
            second.onBindResult(allowed = true)
            awaitUntil("the id settled") { 4802 !in PendingWidgetIds.shared }

            assertThat(widgetsOf(wiring, other.id)).isEmpty()
            assertThat(widgetsOf(wiring, 1)).isEmpty()
            assertThat(binder.deleted).containsExactly(4802)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 19. The profile is the one that is on AT THE TAP. A tap first judges the request against
     * the home screen once more (its settings, its rows), and only then came the store, which read for
     * itself which profile was on: a switch that ended while the tap was being judged made the widget the
     * new profile's, at once or after the bind dialog. The confirmation reads the profile before anything
     * else and hands it to the store, which stores in that profile or not at all.
     */
    @Test fun aWidgetIsNotAddedToAProfileThatCameOnWhileItsTapWasBeingJudged() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val other = (wiring.homeProfiles.create("Other", copyOf = null) as HomeProfileRepository.Created.Done).profile
            val binder = Binder(4804, bindsAtOnce = true)
            val vm = confirmation(wiring, SavedStateHandle(), binder)
            val tapped = AtomicBoolean(false)
            vm.attach(clockRequest(onJudged = switchingOnce(tapped, wiring, to = other.id)))
            awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }

            tapped.set(true)
            vm.add()
            awaitUntil("the confirmation to close") { vm.uiState.value is PinRequestUiState.Finished }

            assertThat(tapped.get()).isFalse()
            assertThat(widgetsOf(wiring, other.id)).isEmpty()
            assertThat(widgetsOf(wiring, 1)).isEmpty()
            assertThat((vm.uiState.value as PinRequestUiState.Finished).failed).isTrue()
            assertThat(binder.deleted).containsExactly(4804)
            assertThat(4804 in PendingWidgetIds.shared).isFalse()
        } finally {
            wiring.close()
        }
    }

    /** The same tap for a shortcut: its row is stored in the profile of the tap, or not at all. */
    @Test fun aShortcutIsNotAddedToAProfileThatCameOnWhileItsTapWasBeingJudged() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val other = (wiring.homeProfiles.create("Other", copyOf = null) as HomeProfileRepository.Created.Done).profile
            val vm = confirmation(wiring, SavedStateHandle(), Binder(4805))
            val tapped = AtomicBoolean(false)
            vm.attach(chatRequest(onJudged = switchingOnce(tapped, wiring, to = other.id)))
            awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }

            tapped.set(true)
            vm.add()
            awaitUntil("the confirmation to close") { vm.uiState.value is PinRequestUiState.Finished }

            assertThat(tapped.get()).isFalse()
            assertThat(wiring.rowsOfProfile(other.id).mapNotNull { it.shortcutId }).isEmpty()
            assertThat(wiring.rowsOfProfile(1).mapNotNull { it.shortcutId }).isEmpty()
            assertThat((vm.uiState.value as PinRequestUiState.Finished).failed).isTrue()
        } finally {
            wiring.close()
        }
    }

    /** And with nothing in between, a shortcut goes to the profile that is on, as it always did. */
    @Test fun aShortcutTappedWithItsProfileOnIsAddedToIt() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val other = (wiring.homeProfiles.create("Other", copyOf = null) as HomeProfileRepository.Created.Done).profile
            val vm = confirmation(wiring, SavedStateHandle(), Binder(4806))
            vm.attach(chatRequest())
            awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }

            vm.add()
            awaitUntil("the confirmation to close") { vm.uiState.value is PinRequestUiState.Finished }

            assertThat(wiring.rowsOfProfile(1).mapNotNull { it.shortcutId }).containsExactly("mia")
            assertThat(wiring.rowsOfProfile(other.id)).isEmpty()
        } finally {
            wiring.close()
        }
    }

    /** What matters is the profile that is on at the answer, not what happened in between: back on the
     *  profile of the tap, the widget is added to it as before. */
    @Test fun aWidgetAllowedWithTheProfileOfTheTapOnAgainIsAddedToIt() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val other = (wiring.homeProfiles.create("Other", copyOf = null) as HomeProfileRepository.Created.Done).profile
            val binder = Binder(4803)
            val vm = confirmation(wiring, SavedStateHandle(), binder)
            addedUpToTheBindDialog(vm)

            assertThat(whileTheMainLooperTurns("the switch") { wiring.homeProfiles.switchTo(other.id) }).isTrue()
            assertThat(whileTheMainLooperTurns("the switch back") { wiring.homeProfiles.switchTo(1) }).isTrue()
            vm.onBindResult(allowed = true)
            awaitUntil("the confirmation to close") { vm.uiState.value is PinRequestUiState.Finished }

            assertThat(widgetsOf(wiring, 1)).containsExactly(4803)
            assertThat(widgetsOf(wiring, other.id)).isEmpty()
            assertThat(binder.deleted).isEmpty()
            assertThat(4803 in PendingWidgetIds.shared).isFalse()
        } finally {
            wiring.close()
        }
    }
}
