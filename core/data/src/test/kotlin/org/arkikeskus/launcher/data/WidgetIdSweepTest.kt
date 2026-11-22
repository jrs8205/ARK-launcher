package org.arkikeskus.launcher.data

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The home screen's sweep of widget host ids next to another app's widget request: an id is freed
 * only when no row holds it and nobody is binding it, and a request's allocation and its step from
 * pending to stored can't fall between the sweep's snapshot and what it does with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetIdSweepTest {

    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private lateinit var repo: HomeLayoutRepository
    private lateinit var store: PinRequestStore
    private val pending = PendingWidgetIds()

    private val clockProvider = ComponentName("com.clock", "com.clock.Analog")
    private val clockAt = PinDecision.Confirm(0, 1, 2, spanX = 2, spanY = 2)
    private val onHome: suspend () -> CurrentPlacement = { CurrentPlacement(clockAt, 4, 6) }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LauncherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.homeItemDao()
        repo = HomeLayoutRepository(db, dao, FakeAppRowResolver())
        store = PinRequestStore(repo, FakeAppRowResolver(), CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { it.pendingWidgetIds = pending }
    }

    @After
    fun tearDown() = db.close()

    private inner class ClockRequest : WidgetPinRequest {
        override val item: PinItem = PinItem.Widget(clockProvider.flattenToString(), "Analog clock", ownProfile = true)
        override val info = AppWidgetProviderInfo().also { it.provider = clockProvider }
        override var isValid: Boolean = true
        override fun accept(): Boolean = false
        override fun accept(appWidgetId: Int): Boolean = true.also { isValid = false }
        override fun loadPreview(context: Context) = PinPreview("Clock", icon = null)
    }

    /** Hands out 100; binds without asking only when [allowed]. */
    private class Binder(private val allowed: Boolean) : WidgetBinder {
        val deleted = ArrayList<Int>()
        override fun allocateId(): Int = 100
        override fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean = allowed
        override fun deleteId(appWidgetId: Int) { deleted += appWidgetId }
    }

    private suspend fun widgetRows() = dao.getContainer(HOME).filter { it.isWidget }

    /** One process's view of the app's private storage; a second instance reads what the first wrote. */
    private class Storage : PendingIdStorage {
        var saved: Map<Int, Long> = emptyMap()
        override fun load(): Map<Int, Long> = saved
        override fun save(ids: Map<Int, Long>) { saved = ids }
    }

    private fun storeIn(pendingIds: PendingWidgetIds) =
        PinRequestStore(repo, FakeAppRowResolver(), CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { it.pendingWidgetIds = pendingIds }

    @Test
    fun anIdARequestHeldWhenItsProcessDiedIsKeptByTheNextProcessesSweep() = runBlocking<Unit> {
        val storage = Storage()
        val before = PendingWidgetIds(now = { 1_000_000L }).apply { persistIn(storage) }
        // The bind dialog is open for 100 when the process dies.
        assertThat(storeIn(before).acceptWidget(ClockRequest(), Binder(allowed = false), 1, onHome))
            .isEqualTo(WidgetPinOutcome.NeedsPermission(100, profileId = 1))

        // The next process: the home screen comes back first and sweeps.
        val after = PendingWidgetIds(now = { 1_000_000L + 60_000L }).apply { persistIn(storage) }
        val swept = ArrayList<Int>()
        after.sweepHostIds(hostIds = { intArrayOf(100) }, rowIds = { emptySet() }, keep = { emptySet() }, delete = { swept += it })

        assertThat(swept).doesNotContain(100)
    }

    @Test
    fun anIdLeftPendingForOverAnHourIsFreedAfterAll() = runBlocking<Unit> {
        val storage = Storage()
        val before = PendingWidgetIds(now = { 1_000_000L }).apply { persistIn(storage) }
        storeIn(before).acceptWidget(ClockRequest(), Binder(allowed = false), 1, onHome)

        // Nobody came back for it: an abandoned dialog must not hold the id forever.
        val later = PendingWidgetIds(now = { 1_000_000L + 2 * 60 * 60 * 1000L }).apply { persistIn(storage) }
        val swept = ArrayList<Int>()
        later.sweepHostIds(hostIds = { intArrayOf(100) }, rowIds = { emptySet() }, keep = { emptySet() }, delete = { swept += it })

        assertThat(swept).containsExactly(100)
    }

    @Test
    fun theSharedPreferencesStorageReadsBackWhatItSaved() {
        val context = RuntimeEnvironment.getApplication()
        SharedPreferencesPendingIdStorage(context).save(mapOf(100 to 1_000L, 101 to 2_000L))

        assertThat(SharedPreferencesPendingIdStorage(context).load()).containsExactly(100, 1_000L, 101, 2_000L)

        SharedPreferencesPendingIdStorage(context).save(emptyMap())
        assertThat(SharedPreferencesPendingIdStorage(context).load()).isEmpty()
    }

    @Test
    fun theSweepFreesOnlyIdsNoRowHoldsNoAddIsBindingAndNoRequestHolds() = runBlocking<Unit> {
        pending.add(3)
        val deleted = ArrayList<Int>()

        val snapshot = pending.sweepHostIds(
            hostIds = { intArrayOf(1, 2, 3, 4) },
            rowIds = { setOf(1) },
            keep = { setOf(4) },
            delete = { deleted += it },
        )

        assertThat(deleted).containsExactly(2)
        assertThat(snapshot).containsExactly(1, 2, 3, 4)
    }

    @Test
    fun aWidgetStoredWhileTheSweepReadsTheRowsIsNotFreed() = runBlocking<Unit> {
        val binder = Binder(allowed = false)
        val request = ClockRequest()
        // The request holds its id while the system's bind dialog is open.
        val outcome = store.acceptWidget(request, binder, 1, onHome)
        assertThat(outcome).isEqualTo(WidgetPinOutcome.NeedsPermission(100, profileId = 1))

        val rowsRead = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val swept = ArrayList<Int>()
        val sweep = launch(Dispatchers.Default) {
            pending.sweepHostIds(
                hostIds = { intArrayOf(100) },
                // Reads the rows (none holds 100 yet), then stalls before acting on what it read.
                rowIds = { repo.boundWidgetIds().also { rowsRead.complete(Unit); resume.await() } },
                keep = { emptySet() },
                delete = { swept += it },
            )
        }
        rowsRead.await()
        // The user allowed the bind: the request stores its row right now.
        val stored = launch(Dispatchers.Default) {
            store.acceptBoundWidget(request, binder, 100, profileId = 1, decide = onHome)
        }
        delay(500)
        resume.complete(Unit)
        sweep.join()
        stored.join()

        assertThat(swept).doesNotContain(100)
        assertThat(widgetRows().map { it.appWidgetId }).containsExactly(100)
    }

    @Test
    fun anIdTheHomeScreenStartsBindingWhileTheSweepWaitsForTheGuardIsKept() = runBlocking<Unit> {
        val host = ArrayList<Int>()
        val inFlight = AtomicReference<Int?>(null) // the home screen's own add (its pendingWidget)
        val swept = ArrayList<Int>()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A widget request holds the guard (storing its row, say).
        val holder = launch(Dispatchers.Default) { pending.guard { holding.complete(Unit); release.await() } }
        holding.await()
        val sweep = launch(Dispatchers.Default) {
            pending.sweepHostIds(
                hostIds = { synchronized(host) { host.toIntArray() } },
                rowIds = { emptySet() },
                keep = { setOfNotNull(inFlight.get()) },
                delete = { swept += it },
            )
        }
        delay(300) // the sweep waits for the guard now
        // The widget picker allocates an id and starts binding it meanwhile.
        synchronized(host) { host += 7 }
        inFlight.set(7)
        release.complete(Unit)
        sweep.join()
        holder.join()

        assertThat(swept).doesNotContain(7)
    }

    @Test
    fun aWidgetAddedWhileTheStartupSweepRunsIsNotTurnedIntoAPlaceholder() = runBlocking<Unit> {
        val inThen = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val sweep = launch(Dispatchers.Default) {
            pending.sweepHostIds(
                hostIds = { intArrayOf() }, // nothing allocated when the host is read
                rowIds = { repo.boundWidgetIds() },
                keep = { emptySet() },
                delete = {},
                // The startup's reverse direction: rows bound to an id the snapshot lacks are unbound.
                then = { snapshot -> inThen.complete(Unit); resume.await(); repo.unbindStaleWidgets(snapshot) },
            )
        }
        inThen.await()
        // Meanwhile another app's widget is allocated, bound, accepted and stored.
        val added = launch(Dispatchers.Default) {
            store.acceptWidget(ClockRequest(), Binder(allowed = true), 1, onHome)
        }
        delay(500)
        resume.complete(Unit)
        sweep.join()
        added.join()

        assertThat(widgetRows().map { it.appWidgetId }).containsExactly(100)
    }
}
