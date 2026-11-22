package org.arkikeskus.launcher.data

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.local.inHomeProfile
import org.arkikeskus.launcher.data.profile.HoldingProfile
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Another app's pin request is decided and stored in ONE home profile. The store's last decision is read
 * from the rows and the grid of the profile that is on; the row it then stores must go to that profile, so
 * the profile stays on from the decision to the row. A switch asked for in between waits and happens after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinRequestOneProfileTest {

    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private lateinit var store: PinRequestStore
    private val active = HoldingProfile()
    private val resolver = FakeAppRowResolver()
    private val pending = PendingWidgetIds()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LauncherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.homeItemDao(active)
        val repo = HomeLayoutRepository(db, dao, resolver)
        store = PinRequestStore(repo, resolver, CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { it.pendingWidgetIds = pending }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun rowsOf(profile: Long) = inHomeProfile(profile) { dao.getAll() }

    /**
     * The decision the store asks for, made on profile 1's home; a switch to profile 2 is asked for while it
     * is being made. The switch is given half a second to happen: without the hold it does, at once.
     */
    private fun CoroutineScope.decidedWhileASwitchIsAsked(placement: PinDecision.Confirm, switch: (Job) -> Unit): suspend () -> CurrentPlacement = {
        val job = launch(Dispatchers.Default) { active.switchTo(2) }
        switch(job)
        withTimeoutOrNull(500) { job.join() }
        CurrentPlacement(placement, columns = 4, rows = 6)
    }

    @Test
    fun aShortcutGoesToTheProfileItWasDecidedInThoughASwitchIsAskedMeanwhile() = runBlocking<Unit> {
        val mia = PinItem.Shortcut("com.chat", "mia", userSerial = 0L, label = "Mia")
        val request = object : PinRequest {
            override val item: PinItem = mia
            override var isValid: Boolean = true
            override fun accept(): Boolean = true.also { isValid = false }
            override fun loadPreview(context: Context) = PinPreview("Chat", icon = null)
        }
        var switch: Job? = null

        val outcome = store.acceptShortcut(request, 1, decidedWhileASwitchIsAsked(PinDecision.Confirm(0, 1, 0)) { switch = it })
        switch!!.join()

        assertThat(outcome).isInstanceOf(ShortcutPinOutcome.Added::class.java)
        assertThat(rowsOf(1).map { it.shortcutId }).containsExactly("mia")
        assertThat(rowsOf(2)).isEmpty()
        // The switch was not lost: it waited for the row and happened after it.
        assertThat(active.state.value).isEqualTo(2L)
    }

    @Test
    fun aWidgetGoesToTheProfileItWasDecidedInThoughASwitchIsAskedMeanwhile() = runBlocking<Unit> {
        val provider = ComponentName("com.clock", "com.clock.Analog")
        val request = object : WidgetPinRequest {
            override val item: PinItem = PinItem.Widget(provider.flattenToString(), "Analog clock", ownProfile = true)
            override val info = AppWidgetProviderInfo().also { it.provider = provider }
            override var isValid: Boolean = true
            override fun accept(): Boolean = false
            override fun accept(appWidgetId: Int): Boolean = true.also { isValid = false }
            override fun loadPreview(context: Context) = PinPreview("Clock", icon = null)
        }
        val binder = object : WidgetBinder {
            override fun allocateId(): Int = 100
            override fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean = true
            override fun deleteId(appWidgetId: Int) = Unit
        }
        var switch: Job? = null

        val outcome = store.acceptWidget(
            request, binder, 1, decidedWhileASwitchIsAsked(PinDecision.Confirm(0, 1, 2, spanX = 2, spanY = 2)) { switch = it },
        )
        switch!!.join()

        assertThat(outcome).isInstanceOf(WidgetPinOutcome.Added::class.java)
        assertThat(rowsOf(1).map { it.appWidgetId }).containsExactly(100)
        assertThat(rowsOf(2)).isEmpty()
        assertThat(active.state.value).isEqualTo(2L)
    }

    /**
     * Review round 18. Android wants the user asked before it binds the widget, and its dialog is another
     * app's window: the user can leave it, switch profile and come back to allow. The widget was confirmed
     * on profile 1's home; the answer must not decide a place for it on profile 2's. The store says which
     * profile the id is for, takes that profile back with the id, and stores the widget only while it is on:
     * otherwise the request is not accepted, nothing is decided, and the id is given back.
     */
    @Test
    fun aWidgetAllowedAfterASwitchIsStoredInNoProfileAndItsIdGivenBack() = runBlocking<Unit> {
        val provider = ComponentName("com.clock", "com.clock.Analog")
        var acceptedWith: Int? = null
        val request = object : WidgetPinRequest {
            override val item: PinItem = PinItem.Widget(provider.flattenToString(), "Analog clock", ownProfile = true)
            override val info = AppWidgetProviderInfo().also { it.provider = provider }
            override var isValid: Boolean = true
            override fun accept(): Boolean = false
            override fun accept(appWidgetId: Int): Boolean = true.also { acceptedWith = appWidgetId }
            override fun loadPreview(context: Context) = PinPreview("Clock", icon = null)
        }
        val deleted = ArrayList<Int>()
        val binder = object : WidgetBinder {
            override fun allocateId(): Int = 100
            override fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean = false
            override fun deleteId(appWidgetId: Int) { deleted += appWidgetId }
        }
        var decided = 0
        val onHome: suspend () -> CurrentPlacement = {
            decided++
            CurrentPlacement(PinDecision.Confirm(0, 1, 2, spanX = 2, spanY = 2), columns = 4, rows = 6)
        }

        val asked = store.acceptWidget(request, binder, 1, onHome)
        assertThat(asked).isEqualTo(WidgetPinOutcome.NeedsPermission(100, profileId = 1))
        // With the dialog open, another profile comes on; then the user allows the bind.
        active.switchTo(2)
        val outcome = store.acceptBoundWidget(request, binder, 100, (asked as WidgetPinOutcome.NeedsPermission).profileId, onHome)

        assertThat(outcome).isEqualTo(WidgetPinOutcome.Failed)
        assertThat(rowsOf(2)).isEmpty()
        assertThat(rowsOf(1)).isEmpty()
        assertThat(acceptedWith).isNull()
        assertThat(decided).isEqualTo(0)
        assertThat(deleted).containsExactly(100)
        assertThat(100 in pending).isFalse()
    }

    /**
     * Review round 19. Which profile the item is for is the caller's to say: the one that was on at the tap,
     * read before the caller judged the request against the home screen. The store read it for itself, later,
     * and took whichever profile was on by then. Handed a profile that is not the one that is on, it accepts
     * nothing: no shortcut, and no widget bound at once.
     */
    @Test
    fun aShortcutConfirmedForAProfileThatIsNoLongerOnIsNotAccepted() = runBlocking<Unit> {
        var accepted = false
        val request = object : PinRequest {
            override val item: PinItem = PinItem.Shortcut("com.chat", "mia", userSerial = 0L, label = "Mia")
            override var isValid: Boolean = true
            override fun accept(): Boolean = true.also { accepted = true }
            override fun loadPreview(context: Context) = PinPreview("Chat", icon = null)
        }
        active.switchTo(2)

        val outcome = store.acceptShortcut(request, profileId = 1) { CurrentPlacement(PinDecision.Confirm(0, 1, 0), columns = 4, rows = 6) }

        assertThat(outcome).isEqualTo(ShortcutPinOutcome.Failed)
        assertThat(accepted).isFalse()
        assertThat(rowsOf(1)).isEmpty()
        assertThat(rowsOf(2)).isEmpty()
    }

    @Test
    fun aWidgetBoundAtOnceForAProfileThatIsNoLongerOnIsNotAcceptedAndItsIdGivenBack() = runBlocking<Unit> {
        val provider = ComponentName("com.clock", "com.clock.Analog")
        var acceptedWith: Int? = null
        val request = object : WidgetPinRequest {
            override val item: PinItem = PinItem.Widget(provider.flattenToString(), "Analog clock", ownProfile = true)
            override val info = AppWidgetProviderInfo().also { it.provider = provider }
            override var isValid: Boolean = true
            override fun accept(): Boolean = false
            override fun accept(appWidgetId: Int): Boolean = true.also { acceptedWith = appWidgetId }
            override fun loadPreview(context: Context) = PinPreview("Clock", icon = null)
        }
        val deleted = ArrayList<Int>()
        val binder = object : WidgetBinder {
            override fun allocateId(): Int = 100
            override fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean = true
            override fun deleteId(appWidgetId: Int) { deleted += appWidgetId }
        }
        active.switchTo(2)

        val outcome = store.acceptWidget(request, binder, profileId = 1) {
            CurrentPlacement(PinDecision.Confirm(0, 1, 2, spanX = 2, spanY = 2), columns = 4, rows = 6)
        }

        assertThat(outcome).isEqualTo(WidgetPinOutcome.Failed)
        assertThat(acceptedWith).isNull()
        assertThat(rowsOf(1)).isEmpty()
        assertThat(rowsOf(2)).isEmpty()
        assertThat(deleted).containsExactly(100)
        assertThat(100 in pending).isFalse()
    }
}
