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
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
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
 * Accepting another app's pin request against a real in-memory Room database: the row exists
 * exactly when the system accepted, duplicates are allowed, the cell is re-checked when the row is
 * stored, and the accept and the row stay together under [ShortcutPinLock].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinRequestStoreTest {

    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private lateinit var repo: HomeLayoutRepository
    private lateinit var store: PinRequestStore
    private val resolver = FakeAppRowResolver()
    private val pending = PendingWidgetIds()

    private val mia = PinItem.Shortcut("com.chat", "mia", userSerial = 0L, label = "Mia")
    private val origin = PinDecision.Confirm(0, 0, 0)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LauncherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.homeItemDao()
        repo = HomeLayoutRepository(db, dao, resolver)
        store = PinRequestStore(repo, resolver, CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { it.pendingWidgetIds = pending }
    }

    @After
    fun tearDown() = db.close()

    /** A request whose accept() is scripted; valid until accepted, like the system's. */
    private class FakeRequest(override val item: PinItem, private val onAccept: () -> Boolean) : PinRequest {
        override var isValid: Boolean = true
        override fun accept(): Boolean = onAccept().also { if (it) isValid = false }
        override fun loadPreview(context: Context) = PinPreview("Chat", icon = null)
    }

    // --- app widgets ---

    private val clockProvider = ComponentName("com.clock", "com.clock.Analog")
    private val clock = PinItem.Widget(clockProvider.flattenToString(), "Analog clock", ownProfile = true)
    private val clockAt = PinDecision.Confirm(0, 1, 2, spanX = 2, spanY = 2)

    /** A widget request whose accept(id) is scripted and remembered. */
    private inner class FakeWidgetRequest(private val onAccept: (Int) -> Boolean) : WidgetPinRequest {
        override val item: PinItem = clock
        override val info = AppWidgetProviderInfo().also { it.provider = clockProvider }
        override var isValid: Boolean = true
        var acceptedWith: Int? = null
        override fun accept(): Boolean = false
        override fun accept(appWidgetId: Int): Boolean =
            onAccept(appWidgetId).also { if (it) { isValid = false; acceptedWith = appWidgetId } }
        override fun loadPreview(context: Context) = PinPreview("Clock", icon = null)
    }

    /** A host and manager that hand out ids from 100 up and remember what was bound and freed. */
    private class FakeBinder(private val allowed: Boolean = true, private val hasIds: Boolean = true) : WidgetBinder {
        private var next = 100
        val allocated = ArrayList<Int>()
        val bound = ArrayList<Int>()
        val deleted = ArrayList<Int>()
        override fun allocateId(): Int? = if (hasIds) (next++).also { allocated += it } else null
        override fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean =
            allowed.also { if (it) bound += appWidgetId }
        override fun deleteId(appWidgetId: Int) { deleted += appWidgetId }
    }

    private suspend fun widgetRows() = dao.getContainer(HOME).filter { it.isWidget }

    /** The decision the store asks for right before the accept: always [placement] on a [columns]×[rows] grid. */
    private fun at(placement: PinDecision, columns: Int = 4, rows: Int = 6): suspend () -> CurrentPlacement =
        { CurrentPlacement(placement, columns, rows) }

    /** Issue #23: the removal tells which shortcut left (for the icon filed under its identity) along
     *  with what stays pinned, so the caller needs no other, possibly stale, view of the row. */
    @Test
    fun removeShortcut_reports_the_removed_shortcut_and_what_stays() = runTest {
        val old = shortcutRow("old", x = 0)
        shortcutRow("kept", x = 1)

        val removed = repo.removeShortcut(old)

        assertThat(removed?.removedShortcutId).isEqualTo("old")
        assertThat(removed?.shortcutIds).containsExactly("kept")
    }

    @Test
    fun aWidgetHomeNoLongerTakesWhenTheAcceptComesIsRefusedWithItsIdFreed() = runTest {
        val binder = FakeBinder()
        val request = FakeWidgetRequest { true }

        // Locked while the add waited for the pin lock or the sweep's guard.
        val outcome = store.acceptWidget(request, binder, at(PinDecision.Locked))

        assertThat(outcome).isEqualTo(WidgetPinOutcome.Refused(PinDecision.Locked))
        assertThat(request.acceptedWith).isNull()
        assertThat(widgetRows()).isEmpty()
        assertThat(binder.deleted).containsExactly(100)
        assertThat(100 in pending).isFalse()
    }

    @Test
    fun aWidgetBoundWithoutAskingIsAcceptedWithItsIdAndStoredWithTheWidgetFields() = runTest {
        val binder = FakeBinder()
        val request = FakeWidgetRequest { true }

        val outcome = store.acceptWidget(request, binder, at(clockAt))

        val rowId = (outcome as WidgetPinOutcome.Added).rowId
        assertThat(binder.bound).containsExactly(100)
        assertThat(request.acceptedWith).isEqualTo(100)
        val row = dao.getById(rowId)!!
        assertThat(row.containerId).isEqualTo(HOME)
        assertThat(row.appWidgetId).isEqualTo(100)
        assertThat(row.widgetProvider).isEqualTo("com.clock/com.clock.Analog")
        assertThat(row.builtinType).isNull()
        assertThat(row.shortcutId).isNull()
        assertThat(row.packageName).isEmpty()
        assertThat(row.spanX to row.spanY).isEqualTo(2 to 2)
        assertThat(row.cell()).isEqualTo(Triple(0, 1, 2))
        assertThat(binder.deleted).isEmpty()
        assertThat(100 in pending).isFalse()
    }

    @Test
    fun aWidgetAcceptTheSystemRefusesFreesTheIdAndStoresNoRow() = runTest {
        val binder = FakeBinder()

        val outcome = store.acceptWidget(FakeWidgetRequest { false }, binder, at(clockAt))

        assertThat(outcome).isEqualTo(WidgetPinOutcome.Failed)
        assertThat(widgetRows()).isEmpty()
        assertThat(binder.deleted).containsExactly(100)
        assertThat(100 in pending).isFalse()
    }

    @Test
    fun aWidgetAcceptThatThrowsFreesTheIdToo() = runTest {
        val binder = FakeBinder()

        val outcome = store.acceptWidget(FakeWidgetRequest { throw IllegalStateException("gone") }, binder, at(clockAt))

        assertThat(outcome).isEqualTo(WidgetPinOutcome.Failed)
        assertThat(widgetRows()).isEmpty()
        assertThat(binder.deleted).containsExactly(100)
    }

    @Test
    fun whenTheSystemWantsTheUserAskedTheIdStaysAllocatedAndProtectedUntilTheAnswer() = runTest {
        val binder = FakeBinder(allowed = false)
        val request = FakeWidgetRequest { true }

        val outcome = store.acceptWidget(request, binder, at(clockAt))

        assertThat(outcome).isEqualTo(WidgetPinOutcome.NeedsPermission(100))
        assertThat(request.acceptedWith).isNull()
        assertThat(widgetRows()).isEmpty()
        assertThat(binder.deleted).isEmpty()
        assertThat(100 in pending).isTrue()

        // The user allowed the bind in the system dialog.
        val accepted = store.acceptBoundWidget(request, binder, 100, at(clockAt))

        assertThat(dao.getById((accepted as WidgetPinOutcome.Added).rowId)!!.appWidgetId).isEqualTo(100)
        assertThat(request.acceptedWith).isEqualTo(100)
        assertThat(100 in pending).isFalse()
    }

    @Test
    fun aBindTheUserDeniedFreesTheId() = runTest {
        val binder = FakeBinder(allowed = false)
        val request = FakeWidgetRequest { true }
        store.acceptWidget(request, binder, at(clockAt))

        store.releaseWidgetId(binder, 100)

        assertThat(binder.deleted).containsExactly(100)
        assertThat(100 in pending).isFalse()
        assertThat(request.acceptedWith).isNull()
        assertThat(widgetRows()).isEmpty()
    }

    @Test
    fun aRowThatCannotBeStoredAfterTheAcceptFreesTheId() = runTest {
        // A 2×2 grid: one 2×2 widget per page. Every page the pager shows fills while the dialog is open.
        for (page in 0 until HomeLayoutRepository.MAX_PAGES) {
            dao.insert(HomeItemEntity(page = page, cellX = 0, cellY = 0, spanX = 2, spanY = 2, appWidgetId = page + 1, widgetProvider = "p"))
        }
        val binder = FakeBinder()
        val request = FakeWidgetRequest { true }

        val outcome = store.acceptWidget(request, binder, at(PinDecision.Confirm(3, 0, 0, 2, 2), columns = 2, rows = 2))

        assertThat(outcome).isEqualTo(WidgetPinOutcome.Failed)
        assertThat(request.acceptedWith).isEqualTo(100)
        assertThat(widgetRows().map { it.appWidgetId }).doesNotContain(100)
        assertThat(binder.deleted).containsExactly(100)
        assertThat(100 in pending).isFalse()
    }

    @Test
    fun aPlacementThatFilledUpMeanwhileGivesWayToTheFirstFreeRectangleOfTheSameSize() = runTest {
        app(0, 1, 2) // lands inside the decided rectangle while the confirmation is open
        val binder = FakeBinder()

        val outcome = store.acceptWidget(FakeWidgetRequest { true }, binder, at(clockAt))

        val row = dao.getById((outcome as WidgetPinOutcome.Added).rowId)!!
        assertThat(row.cell()).isEqualTo(Triple(0, 0, 0))
        assertThat(row.spanX to row.spanY).isEqualTo(2 to 2)
    }

    @Test
    fun withNoIdToBeHadNothingIsAcceptedOrBound() = runTest {
        val binder = FakeBinder(hasIds = false)
        val request = FakeWidgetRequest { true }

        val outcome = store.acceptWidget(request, binder, at(clockAt))

        assertThat(outcome).isEqualTo(WidgetPinOutcome.Failed)
        assertThat(request.acceptedWith).isNull()
        assertThat(binder.bound).isEmpty()
        assertThat(widgetRows()).isEmpty()
    }

    private suspend fun app(page: Int, x: Int, y: Int) = dao.insert(
        HomeItemEntity(packageName = "p$page$x$y", className = "C", page = page, cellX = x, cellY = y),
    )

    private suspend fun shortcutRow(id: String, page: Int = 0, x: Int = 0, y: Int = 0) = dao.insert(
        HomeItemEntity(packageName = "com.chat", shortcutId = id, page = page, cellX = x, cellY = y),
    )

    private suspend fun shortcutRows() = dao.getContainer(HOME).filter { it.isShortcut }

    private fun HomeItemEntity.cell() = Triple(page, cellX, cellY)

    @Test
    fun aRefusedAcceptStoresNothingAndTouchesNoPins() = runTest {
        val rowId = store.accept(FakeRequest(mia) { false }, origin, columns = 4, rows = 6)

        assertThat(rowId).isNull()
        assertThat(shortcutRows()).isEmpty()
        assertThat(resolver.pins).isEmpty()
    }

    @Test
    fun anAcceptThatThrowsStoresNothing() = runTest {
        val rowId = store.accept(FakeRequest(mia) { throw IllegalStateException("already accepted") }, origin, 4, 6)

        assertThat(rowId).isNull()
        assertThat(shortcutRows()).isEmpty()
        assertThat(resolver.pins).isEmpty()
    }

    /** A request another app made up rather than the system: its accept() says yes, but the system
     *  pinned nothing for this launcher. No row, and the app's pin set is put back to what home holds. */
    @Test
    fun aShortcutTheSystemDidNotPinIsNotStored() = runTest {
        shortcutRow("old")
        resolver.pinnedHere = { _, _, _ -> false }

        val rowId = store.accept(FakeRequest(mia) { true }, PinDecision.Confirm(0, 1, 0), 4, 6)

        assertThat(rowId).isNull()
        assertThat(shortcutRows().map { it.shortcutId }).containsExactly("old")
        assertThat(resolver.pins["com.chat" to 0L]).containsExactly("old")
    }

    /** Whether the system pinned it can't be told: the row waits for a pin it can see, as above. */
    @Test
    fun aShortcutWhosePinCannotBeConfirmedIsNotStored() = runTest {
        resolver.pinnedHere = { _, _, _ -> null }

        val rowId = store.accept(FakeRequest(mia) { true }, origin, 4, 6)

        assertThat(rowId).isNull()
        assertThat(shortcutRows()).isEmpty()
    }

    @Test
    fun anAcceptedRequestStoresTheRowAtItsPlacement() = runTest {
        val rowId = store.accept(FakeRequest(mia) { true }, PinDecision.Confirm(0, 2, 1), 4, 6)

        val row = dao.getById(rowId!!)!!
        assertThat(row.containerId).isEqualTo(HOME)
        assertThat(row.packageName).isEqualTo("com.chat")
        assertThat(row.shortcutId).isEqualTo("mia")
        assertThat(row.userSerial).isEqualTo(0L)
        assertThat(row.className).isEmpty()
        assertThat(row.spanX to row.spanY).isEqualTo(1 to 1)
        assertThat(row.cell()).isEqualTo(Triple(0, 2, 1))
    }

    @Test
    fun theSameShortcutAlreadyOnHomeGetsASecondRow() = runTest {
        val first = shortcutRow("mia", x = 0)

        val second = store.accept(FakeRequest(mia) { true }, PinDecision.Confirm(0, 1, 0), 4, 6)

        assertThat(second).isNotNull()
        assertThat(second).isNotEqualTo(first)
        assertThat(shortcutRows().map { it.shortcutId }).containsExactly("mia", "mia")
    }

    @Test
    fun aCellThatFilledUpWhileTheConfirmationWasOpenGivesWayToTheFirstFreeOne() = runTest {
        val placement = decidePinRequest(mia, true, false, dao.getContainer(HOME), 4, 6)
        assertThat(placement).isEqualTo(origin)
        app(0, 0, 0) // lands while the confirmation is open

        val rowId = store.accept(FakeRequest(mia) { true }, placement as PinDecision.Confirm, 4, 6)

        assertThat(dao.getById(rowId!!)!!.cell()).isEqualTo(Triple(0, 1, 0))
    }

    @Test
    fun aPlacementStillFreeIsKeptEvenWhenAnEarlierCellOpenedUp() = runTest {
        val blocker = app(0, 0, 0)
        val placement = decidePinRequest(mia, true, false, dao.getContainer(HOME), 4, 6)
        dao.deleteById(blocker) // the user cleared the origin meanwhile

        val rowId = store.accept(FakeRequest(mia) { true }, placement as PinDecision.Confirm, 4, 6)

        assertThat(dao.getById(rowId!!)!!.cell()).isEqualTo(Triple(0, 1, 0))
    }

    @Test
    fun noRoomLeftAtStoreTimeAddsNothingAndPutsTheSystemPinSetBack() = runTest {
        // A 1×1 grid: one cell per page. All pages but the pager's last are full when the confirmation opens...
        val last = HomeLayoutRepository.MAX_PAGES - 1
        shortcutRow("old", page = 0)
        for (page in 1 until last) app(page, 0, 0)
        val placement = decidePinRequest(mia, true, false, dao.getContainer(HOME), 1, 1)
        assertThat(placement).isEqualTo(PinDecision.Confirm(last, 0, 0))
        // ...and the last one fills up while it is open.
        app(last, 0, 0)
        var accepted = false

        val rowId = store.accept(FakeRequest(mia) { accepted = true; true }, placement as PinDecision.Confirm, 1, 1)

        assertThat(accepted).isTrue()
        assertThat(rowId).isNull()
        assertThat(shortcutRows().map { it.shortcutId }).containsExactly("old")
        // accept() pinned "mia" in the system; the home rows hold only "old", so that is the set again.
        assertThat(resolver.pins["com.chat" to 0L]).containsExactly("old")
    }

    @Test
    fun aRemovalRunningMeanwhileCannotSlipBetweenTheAcceptAndTheRow() = runBlocking<Unit> {
        val old = shortcutRow("old", x = 0)
        var removal: Job? = null
        val request = FakeRequest(mia) {
            // From inside accept(): the user removes the app's other shortcut right now. The removal
            // re-pins what is left on home, so if it ran before the new row existed, "mia" would be
            // pinned nowhere in the system while its row sits on home.
            removal = launch(Dispatchers.Default) {
                ShortcutPinLock.withLock {
                    repo.removeShortcut(old)?.let { resolver.pinShortcuts(it.packageName, it.userSerial, it.shortcutIds) }
                }
            }
            Thread.sleep(300)
            true
        }

        val rowId = store.accept(request, PinDecision.Confirm(0, 1, 0), 4, 6)
        removal!!.join()

        assertThat(rowId).isNotNull()
        assertThat(shortcutRows().map { it.shortcutId }).containsExactly("mia")
        assertThat(resolver.pins["com.chat" to 0L]).containsExactly("mia")
    }
}
