package org.arkikeskus.launcher.data

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.di.ApplicationScope
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What another app asks to put on the home screen ("Add to home screen" in a browser, a
 * conversation shortcut, an app's own "Add widget" button).
 */
sealed interface PinItem {
    /** A deep shortcut; [label] is the name the home screen will show (see AppShortcuts.resolve). */
    data class Shortcut(
        val packageName: String,
        val shortcutId: String,
        val userSerial: Long,
        val label: String,
    ) : PinItem

    /**
     * An app widget. [provider] is the component flattened to a string, as the home row stores it;
     * [label] the provider's name. How many cells the widget takes depends on the home grid's cell
     * size, which only the home screen knows, so the footprint is measured there and handed to
     * [decidePinRequest] as [PinSpans]. [ownProfile] is false for a work-profile provider: those
     * aren't taken (the widget rows carry no profile).
     */
    data class Widget(
        val provider: String,
        val label: String,
        val ownProfile: Boolean,
    ) : PinItem
}

/**
 * An item's footprint on the current grid, in cells: [spanX]×[spanY] is what it gets — for a
 * widget, what the widget picker would give it — and [minSpanX]×[minSpanY] the least it can be.
 * [decidePinRequest] clamps the one to the grid and refuses the request when the other doesn't fit.
 */
data class PinSpans(val spanX: Int, val spanY: Int, val minSpanX: Int, val minSpanY: Int) {
    companion object {
        /** A shortcut's footprint: one cell. */
        val ICON = PinSpans(1, 1, 1, 1)
    }
}

/** What the confirmation shows for a request: the requesting app's name and the item's icon as it
 *  will appear on home (null when it can't be loaded, and for a widget, which has its own preview). */
data class PinPreview(val appLabel: String, val icon: Bitmap?)

/**
 * A requesting app's `LauncherApps.PinItemRequest` behind a small interface: the system class has
 * no public constructor, so the decision and the acceptance paths are exercised with a fake.
 */
interface PinRequest {
    val item: PinItem

    /** False once the request was accepted, or the requesting app withdrew it. */
    val isValid: Boolean

    /** Tells the system the user accepted — the shortcut becomes pinned. False (or an exception)
     *  when the request is no longer valid. A blocking system call. Shortcuts only: a widget is
     *  accepted with the id it was bound to ([WidgetPinRequest.accept]). */
    fun accept(): Boolean

    /** Loads the [PinPreview]; blocking, so call it off the main thread. */
    fun loadPreview(context: Context): PinPreview
}

/** A request for an app widget ([item] is a [PinItem.Widget]), with what binding and the preview need. */
interface WidgetPinRequest : PinRequest {
    /** The provider as the system describes it: component, profile, sizes, preview resources. Its own
     *  preview is what the confirmation shows, not one the request carries: that could be anyone's. */
    val info: AppWidgetProviderInfo

    /** Tells the system the user accepted, and which host id the widget is now bound to
     *  (`EXTRA_APPWIDGET_ID`). False (or an exception) when the request is no longer valid. Blocking. */
    fun accept(appWidgetId: Int): Boolean
}

/** What the confirmation does with a request, decided once when it opens ([decidePinRequest]). */
sealed interface PinDecision {
    /** Nothing to show (an invalid request): close without a word, as Launcher3 does. */
    data object Dismiss : PinDecision

    /** The desktop is locked: say so, add nothing, accept nothing. */
    data object Locked : PinDecision

    /** Every page up to the page limit is full: say so, accept nothing. */
    data object NoSpace : PinDecision

    /** A work-profile widget: say so, accept nothing, allocate nothing. */
    data object WorkProfile : PinDecision

    /** Even the widget's smallest size doesn't fit the grid: say so, accept nothing. */
    data object TooBig : PinDecision

    /** Ask the user; the item goes to this cell with this footprint (re-checked when it is stored). */
    data class Confirm(
        val page: Int,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int = 1,
        val spanY: Int = 1,
    ) : PinDecision
}

/**
 * Pure decision behind the confirmation: the item goes to the first free rectangle of its footprint
 * in reading order from the first page on, onto a new page at the end when none of the existing
 * ones has room, never past the [HomeLayoutRepository.MAX_PAGES] pages the pager shows — and nothing
 * on home is ever moved for it. [homeRows] may be every stored row; only the HOME container counts (folder children carry
 * in-folder cells). A footprint wider or taller than the grid shrinks to it, as the widget picker's
 * does; one whose minimum doesn't fit is refused ([PinDecision.TooBig]).
 */
fun decidePinRequest(
    item: PinItem,
    valid: Boolean,
    desktopLocked: Boolean,
    homeRows: List<HomeItemEntity>,
    columns: Int,
    rows: Int,
    spans: PinSpans = PinSpans.ICON,
): PinDecision {
    if (!valid) return PinDecision.Dismiss
    if (desktopLocked) return PinDecision.Locked
    if (item is PinItem.Widget && !item.ownProfile) return PinDecision.WorkProfile
    val cols = columns.coerceAtLeast(1)
    val rws = rows.coerceAtLeast(1)
    // Launcher3's AddItemActivity (Apache-2.0) refuses a widget whose minimum span exceeds the grid.
    if (spans.minSpanX > cols || spans.minSpanY > rws) return PinDecision.TooBig
    val spanX = spans.spanX.coerceIn(1, cols)
    val spanY = spans.spanY.coerceIn(1, rws)
    val home = homeRows.filter { it.containerId == HOME }
    val (page, cellX, cellY) = HomeLayoutRepository.firstFreeRect(home, cols, spanX, spanY, rws)
    if (!HomeLayoutRepository.isShownPage(page)) return PinDecision.NoSpace
    return PinDecision.Confirm(page, cellX, cellY, spanX, spanY)
}

/**
 * The AppWidgetHost and AppWidgetManager calls a widget pin needs, behind an interface: the
 * acceptance path is exercised with a fake, as [PinRequest] is.
 */
interface WidgetBinder {
    /** A fresh id from the launcher's host, or null when the host can't give one. */
    fun allocateId(): Int?

    /** Binds [appWidgetId] to [info]'s provider without asking. False when the system wants the
     *  user asked first (`ACTION_APPWIDGET_BIND`). */
    fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean

    /** Frees [appWidgetId] in the host (and unbinds it). */
    fun deleteId(appWidgetId: Int)
}

/** Where the item goes on home as it is now, on a grid of [columns]×[rows] — or, not a [PinDecision.Confirm], why not. */
class CurrentPlacement(val decision: PinDecision, val columns: Int, val rows: Int)

/** How [PinRequestStore.acceptShortcut] ended. */
sealed interface ShortcutPinOutcome {
    data class Added(val rowId: Long) : ShortcutPinOutcome
    data class Refused(val decision: PinDecision) : ShortcutPinOutcome
    data object Failed : ShortcutPinOutcome
}

/** How [PinRequestStore.acceptWidget] ended. */
sealed interface WidgetPinOutcome {
    /** Bound, accepted and stored as row [rowId]. */
    data class Added(val rowId: Long) : WidgetPinOutcome

    /** The system wants the user asked: open `ACTION_APPWIDGET_BIND` for [appWidgetId], then
     *  [PinRequestStore.acceptBoundWidget] on OK or [PinRequestStore.releaseWidgetId] on cancel.
     *  [profileId] is the home profile the widget was confirmed for, the one that was on: it is kept with
     *  the id over the dialog and handed back with it, since another profile can be on by the answer. */
    data class NeedsPermission(val appWidgetId: Int, val profileId: Long) : WidgetPinOutcome

    /** Home as it was right before the accept is no [PinDecision.Confirm] ([decision]): nothing was
     *  accepted and no id is left allocated. */
    data class Refused(val decision: PinDecision) : WidgetPinOutcome

    /** No id, the system refused the accept, or the row couldn't be stored: nothing was added and
     *  no id is left allocated. */
    data object Failed : WidgetPinOutcome
}

/**
 * Host ids a widget request has allocated but not yet stored in a home row (or freed). The home
 * screen's sweep of unused host ids leaves these alone: it would otherwise free the id while the
 * system's bind dialog is still open for it. One per process ([shared]); tests take their own.
 *
 * The ids outlive the process ([persistIn]): when it dies with the dialog open, the next one may
 * show the home screen — and sweep — before the confirmation comes back for the id. An id is
 * protected for [MAX_AGE_MS] from when it was allocated (or taken back): a dialog nobody returns
 * from must not hold its id for good.
 *
 * [guard] orders the sweep against a request's own steps: the sweep holds it from reading the host
 * to its last change, a request while it allocates an id and while it moves one from pending to a
 * stored row. Without it a row stored after the sweep read the rows but before it acted lost its
 * id, and a widget added after the startup read the host was unbound into a placeholder.
 */
class PendingWidgetIds(private val now: () -> Long = System::currentTimeMillis) {
    /** Id → when it was allocated or taken back (ms). */
    private val ids = HashMap<Int, Long>()
    private var storage: PendingIdStorage? = null
    private val order = Mutex()

    suspend fun <T> guard(block: suspend () -> T): T = order.withLock { block() }

    /** Keeps the ids in [storage] from now on, taking back the ones a previous process left there
     *  (the expired ones are dropped). Called once, when the app starts, before any sweep. */
    @Synchronized fun persistIn(storage: PendingIdStorage) {
        this.storage = storage
        val oldest = now() - MAX_AGE_MS
        runCatching { storage.load() }.getOrDefault(emptyMap()).forEach { (id, at) ->
            if (at >= oldest && id !in ids) ids[id] = at
        }
        save()
    }

    @Synchronized fun add(appWidgetId: Int) {
        ids[appWidgetId] = now()
        save()
    }

    @Synchronized fun remove(appWidgetId: Int) {
        if (ids.remove(appWidgetId) != null) save()
    }

    @Synchronized operator fun contains(appWidgetId: Int): Boolean {
        val at = ids[appWidgetId] ?: return false
        return now() - at < MAX_AGE_MS
    }

    private fun save() {
        runCatching { storage?.save(ids.toMap()) }
    }

    companion object {
        val shared = PendingWidgetIds()

        /** How long an id stays protected without its confirmation coming back for it. */
        const val MAX_AGE_MS = 60 * 60 * 1000L
    }
}

/** Where [PendingWidgetIds] keeps its ids across a process death: id → when it was allocated (ms). */
interface PendingIdStorage {
    fun load(): Map<Int, Long>
    fun save(ids: Map<Int, Long>)
}

/**
 * The home screen's sweep of the launcher's widget host ids: frees every id in [hostIds] that no
 * home row holds ([rowIds]), that the home screen's own add isn't binding ([keep]) and that no widget
 * request holds (this set). [then] gets the host snapshot (the startup's reverse direction). Returns
 * the snapshot, or null when the host can't be read. [keep] is read together with the host, inside
 * the guard: a sweep that waited for it must not act on an add's id that came meanwhile.
 */
suspend fun PendingWidgetIds.sweepHostIds(
    hostIds: () -> IntArray?,
    rowIds: suspend () -> Set<Int>,
    keep: () -> Set<Int>,
    delete: (Int) -> Unit,
    then: suspend (Set<Int>) -> Unit = {},
): Set<Int>? = guard {
    val snapshot = hostIds()?.toSet() ?: return@guard null
    val kept = keep()
    val stored = rowIds()
    snapshot.filter { it !in stored && it !in kept && it !in this }.forEach(delete)
    then(snapshot)
    snapshot
}

/**
 * Stores a request the user accepted. `accept()` adds the id to the system's pinned set and the
 * row mirrors it, so both happen under [ShortcutPinLock]: an unpin or a restore's re-pin can
 * otherwise slip in between and leave the two apart.
 */
@Singleton
class PinRequestStore @Inject constructor(
    private val homeLayoutRepository: HomeLayoutRepository,
    private val resolver: AppRowResolver,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** The ids [acceptWidget] has allocated and not yet stored or freed; the shared set by default. */
    internal var pendingWidgetIds: PendingWidgetIds = PendingWidgetIds.shared

    /**
     * Accepts a shortcut [request] and stores its home row at [placement] — or at the first free
     * cell when that one filled up while the confirmation was open. Returns the new row's id, or
     * null when nothing was added: `accept()` refused (false or threw) and nothing was stored, or
     * after a successful accept the system shows no pin for it (a request another app made up) or
     * the row could not be stored, in which case the system's pinned set is put back to what the
     * home rows hold. A widget request goes through [acceptWidget].
     */
    suspend fun accept(request: PinRequest, placement: PinDecision.Confirm, columns: Int, rows: Int): Long? {
        val item = request.item as? PinItem.Shortcut ?: return null
        return ShortcutPinLock.withLock {
            withContext(NonCancellable + Dispatchers.IO) { acceptShortcutLocked(request, item, placement, columns, rows) }
        }
    }

    /**
     * Accepts a shortcut [request] where [decide] puts it on home as it is once the pin lock is ours —
     * decided there, not before: a backup restore holding the lock may have locked the desktop or
     * replaced home meanwhile. Not a [PinDecision.Confirm] → [ShortcutPinOutcome.Refused], nothing
     * accepted. The home profile that is on stays on from the decision to the row
     * ([HomeLayoutRepository.whileProfileStays], taken before the pin lock): the place was decided from
     * that profile's rows, grid and lock, and the row is stored in the profile that is on.
     */
    suspend fun acceptShortcut(request: PinRequest, decide: suspend () -> CurrentPlacement): ShortcutPinOutcome =
        homeLayoutRepository.whileProfileStays {
            ShortcutPinLock.withLock {
                withContext(NonCancellable + Dispatchers.IO) {
                    val now = decide()
                    val placement = now.decision as? PinDecision.Confirm
                        ?: return@withContext ShortcutPinOutcome.Refused(now.decision)
                    val item = request.item as? PinItem.Shortcut ?: return@withContext ShortcutPinOutcome.Failed
                    acceptShortcutLocked(request, item, placement, now.columns, now.rows)
                        ?.let { ShortcutPinOutcome.Added(it) } ?: ShortcutPinOutcome.Failed
                }
            }
        }

    /** The accept and its row, with [ShortcutPinLock] held. Once accept() has pinned the id, the row
     *  must follow (or the pin be undone) even if the confirmation is torn down right now. */
    private suspend fun acceptShortcutLocked(
        request: PinRequest, item: PinItem.Shortcut, placement: PinDecision.Confirm, columns: Int, rows: Int,
    ): Long? {
        if (!runCatching { request.accept() }.getOrDefault(false)) return null
        // Only a pin the system really made gets a row: the confirmation is an exported activity, and a
        // request another app made up "accepts" without pinning anything.
        val pinned = runCatching { resolver.isPinnedHere(item.packageName, item.userSerial, item.shortcutId) }.getOrNull() == true
        val rowId = if (!pinned) {
            null
        } else {
            runCatching {
                homeLayoutRepository.insertRequestedShortcut(
                    item.packageName, item.shortcutId, item.userSerial,
                    placement.page, placement.cellX, placement.cellY, columns, rows,
                )
            }.getOrNull()
        }
        if (rowId == null) {
            // The system set may hold the id now but no row mirrors it: pin exactly what home holds.
            runCatching {
                val onHome = homeLayoutRepository.homeShortcutIds(item.packageName, item.userSerial)
                resolver.pinShortcuts(item.packageName, item.userSerial, onHome)
            }
        }
        return rowId
    }

    /**
     * Allocates a host id for the widget [request] and binds it. Bound without asking →
     * [acceptBoundWidget] at once; the system wants the user asked → [WidgetPinOutcome.NeedsPermission]
     * with the id kept allocated (and in [pendingWidgetIds]) for the dialog's outcome; no id or a
     * bind that threw → [WidgetPinOutcome.Failed] with nothing left allocated. The widget is for the home
     * profile that is on as this starts, the one the user said yes on: bound at once or after the dialog,
     * it is stored there or nowhere.
     */
    suspend fun acceptWidget(
        request: WidgetPinRequest, binder: WidgetBinder, decide: suspend () -> CurrentPlacement,
    ): WidgetPinOutcome = withContext(NonCancellable + Dispatchers.IO) {
        val confirmedIn = homeLayoutRepository.profileOn()
        // Protected from the host-id sweep from the moment it exists until it is in a row or freed;
        // allocated under the guard, so a sweep running now has either seen it pending or not seen it.
        val id = pendingWidgetIds.guard {
            runCatching { binder.allocateId() }.getOrNull()?.also { pendingWidgetIds.add(it) }
        } ?: return@withContext WidgetPinOutcome.Failed
        val bound = runCatching { binder.bindIfAllowed(id, request.info) }
        if (bound.isFailure) {
            free(binder, id)
            return@withContext WidgetPinOutcome.Failed
        }
        if (!bound.getOrThrow()) return@withContext WidgetPinOutcome.NeedsPermission(id, confirmedIn)
        acceptBoundWidget(request, binder, id, confirmedIn, decide)
    }

    /**
     * Accepts the widget [request] with [appWidgetId], already bound, where [decide] puts it on home
     * as it is right before the accept — once the pin lock and the sweep's guard are ours: a restore
     * holding the one may have locked the desktop or replaced home, and so may anything while the
     * sweep held the other. The row goes to the decided rectangle, or to the first free one of its
     * size when that filled up meanwhile. The id leaves [pendingWidgetIds] either way: into the row,
     * or freed in the host — not a [PinDecision.Confirm] ([WidgetPinOutcome.Refused]), the system
     * refused the accept or the row couldn't be stored ([WidgetPinOutcome.Failed]). As for a shortcut
     * ([acceptShortcut]), the home profile that is on stays on from the decision to the row.
     *
     * And that profile is [profileId], the one the widget was confirmed for
     * ([WidgetPinOutcome.NeedsPermission.profileId]), or nothing is added ([WidgetPinOutcome.Failed], the
     * id freed, the request not accepted). Android's bind dialog is another app's window: the user can
     * leave it, switch profile in Settings and come back to allow, and a place decided at the answer on
     * the profile that is on by then would put the widget on a home screen it was never confirmed for.
     */
    suspend fun acceptBoundWidget(
        request: WidgetPinRequest, binder: WidgetBinder, appWidgetId: Int, profileId: Long,
        decide: suspend () -> CurrentPlacement,
    ): WidgetPinOutcome = withContext(NonCancellable + Dispatchers.IO) {
        // The order of the three: the profile's hold, the pin lock, the sweep's guard. Under the guard the
        // id goes from pending to stored in one step: the sweep sees it in one or the other.
        val outcome = homeLayoutRepository.whileProfileIsOn(profileId) {
            ShortcutPinLock.withLock {
                pendingWidgetIds.guard {
                    val now = decide()
                    val placement = now.decision as? PinDecision.Confirm
                        ?: return@guard WidgetPinOutcome.Refused(now.decision)
                    val item = request.item as? PinItem.Widget
                    if (item == null || !runCatching { request.accept(appWidgetId) }.getOrDefault(false)) {
                        return@guard WidgetPinOutcome.Failed
                    }
                    runCatching {
                        homeLayoutRepository.insertRequestedWidget(
                            appWidgetId, item.provider,
                            placement.page, placement.cellX, placement.cellY, placement.spanX, placement.spanY,
                            now.columns, now.rows,
                        )
                    }.getOrNull()?.let { rowId ->
                        pendingWidgetIds.remove(appWidgetId)
                        WidgetPinOutcome.Added(rowId)
                    } ?: WidgetPinOutcome.Failed
                }
            }
        } ?: WidgetPinOutcome.Failed // Another profile is on: not this home screen's widget.
        // An accepted request whose row failed leaves the id bound to nothing: freeing it unbinds.
        if (outcome !is WidgetPinOutcome.Added) free(binder, appWidgetId)
        outcome
    }

    /** Takes [appWidgetId] back under the sweep's protection: the confirmation of a process recreated
     *  while the bind dialog was open still holds the id (in its saved state), but the set was lost. */
    suspend fun holdWidgetId(appWidgetId: Int) {
        pendingWidgetIds.guard { pendingWidgetIds.add(appWidgetId) }
    }

    /**
     * Frees an id [acceptWidget] left allocated — the user cancelled the bind dialog, or the
     * confirmation has nothing left to add it to. Never an id a home row holds: a confirmation
     * recreated from saved state older than its own accept still names the id its widget was stored
     * with; that one only leaves [pendingWidgetIds]. Judged under the guard, against a row being stored.
     */
    suspend fun releaseWidgetId(binder: WidgetBinder, appWidgetId: Int) {
        withContext(NonCancellable + Dispatchers.IO) {
            pendingWidgetIds.guard {
                val stored = runCatching { appWidgetId in homeLayoutRepository.boundWidgetIds() }.getOrDefault(true)
                if (stored) pendingWidgetIds.remove(appWidgetId) else free(binder, appWidgetId)
            }
        }
    }

    /** [releaseWidgetId] for a caller with no coroutine left — the confirmation being torn down
     *  while the bind dialog's answer is still out: run on the application's scope. */
    fun abandonWidgetId(binder: WidgetBinder, appWidgetId: Int) {
        scope.launch { releaseWidgetId(binder, appWidgetId) }
    }

    private fun free(binder: WidgetBinder, appWidgetId: Int) {
        runCatching { binder.deleteId(appWidgetId) }
        pendingWidgetIds.remove(appWidgetId)
    }
}
