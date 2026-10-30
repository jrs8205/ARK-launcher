package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.PendingWidgetIds
import org.arkikeskus.launcher.data.PinItem
import org.arkikeskus.launcher.data.PinPreview
import org.arkikeskus.launcher.data.PinRequestStore
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.data.WidgetBinder
import org.arkikeskus.launcher.data.WidgetPinRequest
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The widget path of the confirmation through the system's bind dialog: an answer that arrives in a
 * recreated process, an answer that arrives after the confirmation went away, and a desktop locked
 * while the dialog was open. Real repositories over an in-memory database (built reflectively: Room
 * and DataStore are not on this module's test compile classpath, as in HomeViewModelInitTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PinRequestViewModelTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private lateinit var settings: SettingsRepository
    private lateinit var store: PinRequestStore

    private object Resolver : AppRowResolver {
        override fun launchTargets(packageName: String, userSerial: Long) = error("unused")
        override fun missingShortcuts(packageName: String, userSerial: Long, shortcutIds: Collection<String>) = emptySet<String>()
        override fun isTemporarilyUnavailable(packageName: String, userSerial: Long) = false
        override fun ownUserSerial(): Long = 0L
        override fun pinShortcuts(packageName: String, userSerial: Long, shortcutIds: List<String>) = true
        override fun isPinnedHere(packageName: String, userSerial: Long, shortcutId: String) = true
    }

    @Before
    fun setUp() {
        db = Class.forName("androidx.room.Room")
            .getMethod("inMemoryDatabaseBuilder", Context::class.java, Class::class.java)
            .invoke(null, context, LauncherDatabase::class.java)
            .let { builder -> builder.javaClass.getMethod("allowMainThreadQueries").invoke(builder) }
            .let { builder -> builder.javaClass.getMethod("build").invoke(builder) as LauncherDatabase }
        dao = LauncherDatabase::class.java.getMethod("homeItemDao").invoke(db) as HomeItemDao
        val dataStore: Any = Class.forName("androidx.datastore.preferences.core.PreferenceDataStoreFactory").let { factory ->
            val file = File.createTempFile("launcher_settings", ".preferences_pb", context.cacheDir).also { it.delete() }
            val produceFile: () -> File = { file }
            factory.methods
                .single { it.name == "create" && it.parameterCount == 4 && it.parameterTypes.last() == Function0::class.java }
                .invoke(factory.getField("INSTANCE").get(null), null, emptyList<Any>(), dataStoreScope, produceFile)
        }
        settings = SettingsRepository::class.java.constructors.single().newInstance(dataStore) as SettingsRepository
        store = PinRequestStore(HomeLayoutRepository(db, dao, Resolver), Resolver, dataStoreScope)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        db.javaClass.getMethod("close").invoke(db)
    }

    private val clockProvider = ComponentName("com.clock", "com.clock.Analog")

    /** A fresh wrapper per call, as a recreated activity hands over. Sizes in px = dp (density 1).
     *  Counts how often it was judged. */
    private fun clockRequest(
        minWidth: Int = 0,
        minHeight: Int = 0,
        judged: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(),
        valid: Boolean = true,
    ) = object : WidgetPinRequest {
        override val item: PinItem = PinItem.Widget(clockProvider.flattenToString(), "Analog clock", ownProfile = true)
        override val info = AppWidgetProviderInfo().also {
            it.provider = clockProvider
            it.minWidth = minWidth
            it.minHeight = minHeight
        }
        override val isValid: Boolean get() = valid.also { judged.incrementAndGet() }
        override fun accept(): Boolean = false
        override fun accept(appWidgetId: Int): Boolean = true
        override fun loadPreview(context: Context) = PinPreview("Clock", icon = null)
    }

    /** A conversation shortcut whose accept() the system grants; counts how often it was judged. */
    private fun chatRequest(judged: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()) =
        object : org.arkikeskus.launcher.data.PinRequest {
        override val item: PinItem = PinItem.Shortcut("com.chat", "mia", userSerial = 0L, label = "Mia")
        override val isValid: Boolean get() = true.also { judged.incrementAndGet() }
        override fun accept(): Boolean = true
        override fun loadPreview(context: Context) = PinPreview("Chat", icon = null)
    }

    /** Hands out [id]; binds without asking only when [allowed]; [bindGate] holds the bind call when set. */
    private class Binder(
        private val id: Int,
        private val bindGate: CountDownLatch? = null,
        private val allowed: Boolean = false,
    ) : WidgetBinder {
        val deleted = ArrayList<Int>()
        val bindStarted = CountDownLatch(1)
        override fun allocateId(): Int = id
        override fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean {
            bindStarted.countDown()
            bindGate?.await(5, TimeUnit.SECONDS)
            return allowed
        }
        override fun deleteId(appWidgetId: Int) { synchronized(deleted) { deleted += appWidgetId } }
    }

    private fun viewModel(saved: SavedStateHandle, binder: Binder) =
        PinRequestViewModel(context, settings, HomeLayoutRepository(db, dao, Resolver), store, saved).also {
            it.binder = binder
            it.newItems = NewHomeItems()
            it.cellSize = HomeCellSize().apply { publish(80f, 100f, columns = 4, rows = 6) }
        }

    /** Runs the main looper (the ViewModel's scope) until [done], giving the IO work time. */
    private fun awaitUntil(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun widgetIds() = runBlocking { dao.getContainer(HOME).filter { it.isWidget }.map { it.appWidgetId } }

    private fun confirmOpen(vm: PinRequestViewModel) {
        vm.attach(clockRequest())
        awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }
    }

    @Test
    fun aBindAllowedAfterTheProcessWasRecreatedStillAddsTheWidget() {
        val saved = SavedStateHandle()
        val first = viewModel(saved, Binder(4201))
        confirmOpen(first)
        first.add()
        awaitUntil("the bind dialog") { (first.uiState.value as? PinRequestUiState.Confirm)?.bindRequest != null }

        // The process dies with the system's dialog open: memory is gone, the saved state is not.
        PendingWidgetIds.shared.remove(4201)
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val second = viewModel(restored, Binder(4201))
        awaitUntil("the id held again") { 4201 in PendingWidgetIds.shared }
        // The dialog's answer can come before the recreated screen hands over its request.
        second.onBindResult(allowed = true)
        second.attach(clockRequest())
        awaitUntil("the widget stored") { widgetIds().isNotEmpty() }

        assertThat(widgetIds()).containsExactly(4201)
        assertThat(4201 in PendingWidgetIds.shared).isFalse()
    }

    /**
     * The system saved the confirmation's state while the bind dialog was open; the widget was then
     * allowed, accepted and stored, and the process died before the confirmation closed. The activity
     * comes back from that older state — still naming the id, now the stored widget's — with the
     * request accepted already. Returns the binder of the recreated confirmation, its view model and
     * the id.
     */
    private fun storedThenRecreatedFromOlderState(id: Int): Triple<Binder, PinRequestViewModel, Int> {
        val saved = SavedStateHandle()
        val first = viewModel(saved, Binder(id))
        confirmOpen(first)
        first.add()
        awaitUntil("the bind dialog") { (first.uiState.value as? PinRequestUiState.Confirm)?.bindRequest != null }
        val older = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        first.onBindResult(allowed = true)
        awaitUntil("the widget stored") { id in widgetIds() }
        val binder = Binder(id)
        return Triple(binder, viewModel(older, binder), id)
    }

    @Test
    fun aConfirmationRecreatedFromStateOlderThanItsAcceptKeepsTheStoredWidgetsId() {
        val (binder, recreated, id) = storedThenRecreatedFromOlderState(4601)

        recreated.attach(clockRequest(valid = false))
        awaitUntil("the confirmation closed") { recreated.uiState.value is PinRequestUiState.Finished }
        awaitUntil("the id settled") { id !in PendingWidgetIds.shared }

        assertThat(binder.deleted).doesNotContain(id)
        assertThat(widgetIds()).contains(id)
    }

    @Test
    fun aConfirmationRecreatedFromOlderStateAndGoneBeforeItDecidedKeepsTheStoredWidgetsId() {
        val (binder, recreated, id) = storedThenRecreatedFromOlderState(4602)
        awaitUntil("the id held again") { id in PendingWidgetIds.shared }

        // A newer request replaces it before it was handed its own.
        ViewModelStore().run {
            put("pin", recreated)
            clear()
        }
        awaitUntil("the id settled") { id !in PendingWidgetIds.shared }

        assertThat(synchronized(binder.deleted) { binder.deleted.toList() }).doesNotContain(id)
        assertThat(widgetIds()).contains(id)
    }

    @Test
    fun aBindAskedForAfterTheConfirmationWentAwayIsReleased() {
        val gate = CountDownLatch(1)
        val binder = Binder(4202, bindGate = gate)
        val vm = viewModel(SavedStateHandle(), binder)
        confirmOpen(vm)
        vm.add()
        awaitUntil("the bind call") { binder.bindStarted.count == 0L }
        // A newer request replaces the confirmation while the bind call is still out.
        ViewModelStore().run {
            put("pin", vm)
            clear()
        }
        gate.countDown()
        awaitUntil("the id freed") { synchronized(binder.deleted) { 4202 in binder.deleted } }

        assertThat(4202 in PendingWidgetIds.shared).isFalse()
        assertThat(widgetIds()).isEmpty()
    }

    @Test
    fun aGridChangedWhileTheHomeScreenWasAwayMeasuresTheWidgetOnTheNewGrid() {
        runBlocking {
            settings.setHomeColumns(6)
            settings.setHomeRows(6)
        }
        val vm = viewModel(SavedStateHandle(), Binder(4204))
        // Measured when the home screen last laid out: 4 columns of 100 dp.
        vm.cellSize = HomeCellSize().apply { publish(100f, 100f, columns = 4, rows = 6) }

        vm.attach(clockRequest(minWidth = 300))
        awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }

        // 300 dp on the 400 dp grid split six ways: five cells, not the old grid's three.
        assertThat((vm.uiState.value as PinRequestUiState.Confirm).spanX).isEqualTo(5)
    }

    @Test
    fun aWidgetThatOpensASecondPageIsMeasuredOnTheGridTheDotsLeaveIt() {
        runBlocking {
            settings.setHomeColumns(4)
            settings.setHomeRows(6)
            // The only page is full.
            dao.insert(HomeItemEntity(page = 0, cellX = 0, cellY = 0, spanX = 4, spanY = 6, appWidgetId = 1, widgetProvider = "p"))
        }
        val now = runBlocking { settings.settings.first() }
        val vm = viewModel(SavedStateHandle(), Binder(4211, allowed = true))
        // Measured as home is now, one page and no dots: 450 dp, 75 a row.
        vm.cellSize = HomeCellSize().apply { publish(75f, 75f, columns = 4, rows = 6, chrome = workspaceChrome(now, pageCount = 1, fontScale = 1f, dockIcons = 0)) }

        vm.attach(clockRequest(minHeight = 440))
        awaitUntil("the decision") { vm.uiState.value !is PinRequestUiState.Loading }

        // The widget would open a second page, whose dots leave the grid 425 dp: 440 needs seven rows of six.
        // (By name: the test provider's info can't print itself.)
        assertThat(vm.uiState.value::class.simpleName).isEqualTo("TooBig")
    }

    /** With its labels shown the dock's height follows its icons' size, and that the number of them: seven
     *  small ones measured, the dock cut to four in Settings while home stayed away — larger icons, a
     *  taller dock, a shorter grid. The measured cell is not that grid's. */
    @Test
    fun aDockCutToFewerLargerIconsWhileHomeWasAwayIsNotMeasuredOnTheOldGrid() {
        runBlocking {
            settings.setHomeColumns(4)
            settings.setHomeRows(6)
            settings.setShowDockLabels(true)
            for (i in 1..7) {
                dao.insert(HomeItemEntity(containerId = HomeItemEntity.DOCK, packageName = "com.app$i", className = "Main", page = 0, cellX = i - 1, cellY = 0))
            }
            settings.setDockColumns(7)
        }
        val measured = runBlocking { settings.settings.first() }
        val vm = viewModel(SavedStateHandle(), Binder(4213))
        // The home screen as last laid out: seven 32 dp icons, a 72 dp dock row, 346 dp of grid.
        vm.cellSize = HomeCellSize().apply {
            publish(80f, 346f / 6, columns = 4, rows = 6, chrome = workspaceChrome(measured, pageCount = 1, fontScale = 1f, dockIcons = 7))
        }
        runBlocking { settings.setDockColumns(4) }

        vm.attach(clockRequest(minHeight = 340))
        awaitUntil("the decision") { vm.uiState.value !is PinRequestUiState.Loading }

        // Four 52 dp icons over their labels leave the grid about 329 dp: 340 is more than its six rows.
        assertThat(vm.uiState.value::class.simpleName).isEqualTo("TooBig")
    }

    @Test
    fun beforeTheWorkspaceHasMeasuredTheWidgetIsEstimatedInTheConfirmationsWindow() {
        runBlocking {
            settings.setHomeColumns(4)
            settings.setHomeRows(6)
        }
        val vm = viewModel(SavedStateHandle(), Binder(4212))
        vm.cellSize = HomeCellSize() // nothing measured in this process

        // A 24 dp status bar and a 48 dp navigation bar under the dock leave the grid 274 dp of the 470.
        vm.attach(clockRequest(minHeight = 260), HomeWindow(320f, 470f, statusBarDp = 24f, navigationBarDp = 48f))
        awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }

        // 260 dp needs six of its 45.7 dp rows (five of the 57.7 dp ones a window without bars gives).
        assertThat((vm.uiState.value as PinRequestUiState.Confirm).spanY).isEqualTo(6)
    }

    private fun widgetSpanX() = runBlocking { dao.getContainer(HOME).filter { it.isWidget }.map { it.spanX } }

    /** 4 columns of 100 dp when the confirmation opens; [change] runs while it is open. */
    private fun confirmOnFourColumns(binder: Binder): PinRequestViewModel {
        runBlocking {
            settings.setHomeColumns(4)
            settings.setHomeRows(6)
        }
        val vm = viewModel(SavedStateHandle(), binder)
        vm.cellSize = HomeCellSize().apply { publish(100f, 100f, columns = 4, rows = 6) }
        vm.attach(clockRequest(minWidth = 300))
        awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }
        assertThat((vm.uiState.value as PinRequestUiState.Confirm).spanX).isEqualTo(3)
        return vm
    }

    @Test
    fun aGridChangedWhileTheConfirmationWasOpenSizesTheWidgetOnTheNewGrid() {
        val vm = confirmOnFourColumns(Binder(4206, allowed = true))

        runBlocking { settings.setHomeColumns(6) }
        vm.add()
        awaitUntil("the widget stored") { widgetSpanX().isNotEmpty() }

        // 300 dp on the 400 dp grid split six ways: five cells, not the three it had on four.
        assertThat(widgetSpanX()).containsExactly(5)
    }

    @Test
    fun aGridChangedWhileTheBindDialogWasOpenSizesTheWidgetOnTheNewGrid() {
        val vm = confirmOnFourColumns(Binder(4207))
        vm.add()
        awaitUntil("the bind dialog") { (vm.uiState.value as? PinRequestUiState.Confirm)?.bindRequest != null }

        runBlocking { settings.setHomeColumns(6) }
        vm.onBindResult(allowed = true)
        awaitUntil("the widget stored") { widgetSpanX().isNotEmpty() }

        assertThat(widgetSpanX()).containsExactly(5)
    }

    @Test
    fun aDesktopLockedByARestoreHoldingThePinLockAddsNoShortcut() {
        val vm = viewModel(SavedStateHandle(), Binder(4208))
        val judged = java.util.concurrent.atomic.AtomicInteger()
        vm.attach(chatRequest(judged))
        awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }
        val judgedAtOpen = judged.get()
        // A backup restore holds the pin lock; it brings back a locked desktop before letting go.
        val restoring = java.util.concurrent.CountDownLatch(1)
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val restore = CoroutineScope(Dispatchers.Default).launch {
            ShortcutPinLock.withLock {
                restoring.countDown()
                finish.await()
                settings.setDesktopLocked(true)
            }
        }
        check(restoring.await(5, TimeUnit.SECONDS)) { "the restore never got the pin lock" }

        vm.add()
        // Let the add judge the request (the desktop still open) and reach the pin lock the restore holds.
        awaitUntil("the add's check") { judged.get() > judgedAtOpen }
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        finish.complete(Unit)
        // Not runBlocking: the main thread must keep running the ViewModel's reads meanwhile.
        awaitUntil("the restore") { restore.isCompleted }
        awaitUntil("the outcome") { vm.uiState.value.let { it !is PinRequestUiState.Confirm } }

        assertThat(vm.uiState.value).isEqualTo(PinRequestUiState.Locked)
        assertThat(runBlocking { dao.getContainer(HOME).filter { it.isShortcut } }).isEmpty()
    }

    /**
     * The add judges the widget request with the desktop open, then waits for [lock] (which another
     * job holds); the desktop is locked before that job lets go. Returns the add's outcome.
     */
    private fun addWhileHeld(binder: Binder, lock: suspend (suspend () -> Unit) -> Unit): PinRequestUiState {
        val vm = viewModel(SavedStateHandle(), binder)
        val judged = java.util.concurrent.atomic.AtomicInteger()
        vm.attach(clockRequest(judged = judged))
        awaitUntil("the confirmation") { vm.uiState.value is PinRequestUiState.Confirm }
        val judgedAtOpen = judged.get()
        val holding = CountDownLatch(1)
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holder = CoroutineScope(Dispatchers.Default).launch {
            lock {
                holding.countDown()
                finish.await()
                settings.setDesktopLocked(true)
            }
        }
        check(holding.await(5, TimeUnit.SECONDS)) { "the lock was never taken" }

        vm.add()
        awaitUntil("the add's check") { judged.get() > judgedAtOpen }
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        finish.complete(Unit)
        awaitUntil("the holder") { holder.isCompleted }
        awaitUntil("the outcome") { vm.uiState.value.let { it !is PinRequestUiState.Confirm } }
        return vm.uiState.value
    }

    @Test
    fun aDesktopLockedWhileTheWidgetWaitedForTheSweepAddsNothing() {
        val binder = Binder(4209, allowed = true)

        // The home screen's sweep of unused host ids holds the guard meanwhile.
        val outcome = addWhileHeld(binder) { block -> PendingWidgetIds.shared.guard { block() } }

        assertThat(outcome).isEqualTo(PinRequestUiState.Locked)
        assertThat(widgetIds()).isEmpty()
        assertThat(binder.deleted).containsExactly(4209)
        assertThat(4209 in PendingWidgetIds.shared).isFalse()
    }

    @Test
    fun aDesktopLockedByARestoreHoldingThePinLockAddsNoWidget() {
        val binder = Binder(4210, allowed = true)

        // A backup restore holds the pin lock; it brings back a locked desktop before letting go.
        val outcome = addWhileHeld(binder) { block -> ShortcutPinLock.withLock { block() } }

        assertThat(outcome).isEqualTo(PinRequestUiState.Locked)
        assertThat(widgetIds()).isEmpty()
        assertThat(4210 in PendingWidgetIds.shared).isFalse()
    }

    @Test
    fun aDesktopLockedWhileTheBindDialogWasOpenAddsNothing() {
        val binder = Binder(4203)
        val vm = viewModel(SavedStateHandle(), binder)
        confirmOpen(vm)
        vm.add()
        awaitUntil("the bind dialog") { (vm.uiState.value as? PinRequestUiState.Confirm)?.bindRequest != null }

        runBlocking { settings.setDesktopLocked(true) }
        vm.onBindResult(allowed = true)
        awaitUntil("the outcome") { vm.uiState.value !is PinRequestUiState.Confirm }

        assertThat(vm.uiState.value).isEqualTo(PinRequestUiState.Locked)
        assertThat(widgetIds()).isEmpty()
        assertThat(binder.deleted).containsExactly(4203)
        assertThat(4203 in PendingWidgetIds.shared).isFalse()
    }
}
