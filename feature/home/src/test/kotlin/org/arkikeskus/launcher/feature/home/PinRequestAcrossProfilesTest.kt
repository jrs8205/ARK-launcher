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
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.PendingWidgetIds
import org.arkikeskus.launcher.data.PinItem
import org.arkikeskus.launcher.data.PinPreview
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

    /** A fresh wrapper per call, as a recreated activity hands over. */
    private fun clockRequest() = object : WidgetPinRequest {
        override val item: PinItem = PinItem.Widget(clockProvider.flattenToString(), "Analog clock", ownProfile = true)
        override val info = AppWidgetProviderInfo().also { it.provider = clockProvider }
        override val isValid: Boolean = true
        override fun accept(): Boolean = false
        override fun accept(appWidgetId: Int): Boolean = true
        override fun loadPreview(context: Context) = PinPreview("Clock", icon = null)
    }

    /** Hands out [id] and never binds without the user being asked. */
    private class AskingBinder(private val id: Int) : WidgetBinder {
        val deleted = ArrayList<Int>()
        override fun allocateId(): Int = id
        override fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean = false
        override fun deleteId(appWidgetId: Int) { synchronized(deleted) { deleted += appWidgetId } }
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
            val binder = AskingBinder(4801)
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
            addedUpToTheBindDialog(confirmation(wiring, saved, AskingBinder(4802)))

            // The process dies with the dialog open: memory is gone, the saved state is not.
            PendingWidgetIds.shared.remove(4802)
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            assertThat(whileTheMainLooperTurns("the switch") { wiring.homeProfiles.switchTo(other.id) }).isTrue()
            val binder = AskingBinder(4802)
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

    /** What matters is the profile that is on at the answer, not what happened in between: back on the
     *  profile of the tap, the widget is added to it as before. */
    @Test fun aWidgetAllowedWithTheProfileOfTheTapOnAgainIsAddedToIt() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val other = (wiring.homeProfiles.create("Other", copyOf = null) as HomeProfileRepository.Created.Done).profile
            val binder = AskingBinder(4803)
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
