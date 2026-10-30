package org.arkikeskus.launcher.feature.home

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.PinDecision
import org.arkikeskus.launcher.data.PinItem
import org.arkikeskus.launcher.data.PinRequest
import org.arkikeskus.launcher.data.PinRequestStore
import org.arkikeskus.launcher.data.PinSpans
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.WidgetBinder
import org.arkikeskus.launcher.data.WidgetPinOutcome
import org.arkikeskus.launcher.data.WidgetPinRequest
import org.arkikeskus.launcher.data.CurrentPlacement
import org.arkikeskus.launcher.data.ShortcutPinOutcome
import org.arkikeskus.launcher.data.decidePinRequest
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject

/**
 * Drives the confirmation of another app's pin request: decides once what to show
 * ([decidePinRequest]), loads the preview off the main thread, and on "Add to home screen" accepts
 * and stores the row through [PinRequestStore] — once, whatever the activity does meanwhile. A
 * widget is bound first, through the system's bind dialog when it must be ([onBindResult]). Not
 * [HomeViewModel]: that one seeds, sweeps and listens on creation, none of which belongs here.
 */
@HiltViewModel
class PinRequestViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
    private val pinRequestStore: PinRequestStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PinRequestUiState>(PinRequestUiState.Loading)
    val uiState: StateFlow<PinRequestUiState> = _uiState

    private var request: PinRequest? = null

    /** Rows the home screen reveals on its next return; the shared hand-over by default. */
    internal var newItems: NewHomeItems = NewHomeItems.shared

    /** The grid's cell size as the workspace published it; the shared value by default. */
    internal var cellSize: HomeCellSize = HomeCellSize.shared

    /** The host and manager a widget is bound through; built for the launcher's own host. */
    internal var binder: WidgetBinder = SystemWidgetBinder(context)

    /**
     * The host id waiting on the system's bind dialog, if any — freed if the confirmation goes away
     * first. Kept in the saved state: a process recreated while the dialog is open (the dialog is
     * another app's window) still knows the id, and the dialog's answer reaches it.
     */
    private var awaitingBind: Int? = savedState.get<Int>(KEY_AWAITING_BIND)
        set(value) {
            field = value
            savedState[KEY_AWAITING_BIND] = value
        }

    /** A bind answer that came before the request was handed over (a recreated process). */
    private var earlyBindAnswer: Boolean? = null

    /** Set when the confirmation is gone; an id a bind call hands back after that is freed at once. */
    private var cleared = false

    /** The window the confirmation shows in, the home screen's as well: what the estimate of the
     *  grid lays out in before the workspace has measured it ([HomeCellSize.estimate]). */
    @Volatile private var homeWindow: HomeWindow? = null

    init {
        // The process was recreated while the bind dialog was open: the id is still allocated, but
        // the set that kept the home screen's sweep off it went with the old process.
        awaitingBind?.let { id -> viewModelScope.launch { withContext(NonCancellable) { pinRequestStore.holdWidgetId(id) } } }
    }

    /** Takes the request the activity was started with, and the [window] it shows in. Only the first
     *  request counts: a recreated activity hands over the same one again — and its window, which is
     *  the one in force now (a large screen turned meanwhile). */
    internal fun attach(request: PinRequest, window: HomeWindow? = null) {
        window?.let { homeWindow = it }
        if (this.request != null) return
        this.request = request
        viewModelScope.launch {
            val now = decideNow(request)
            val decision = now.decision
            val cell = now.cell
            _uiState.value = when (decision) {
                PinDecision.Dismiss -> PinRequestUiState.Finished()
                PinDecision.Locked -> PinRequestUiState.Locked
                PinDecision.NoSpace -> PinRequestUiState.NoSpace
                PinDecision.WorkProfile -> PinRequestUiState.WorkProfile
                PinDecision.TooBig -> PinRequestUiState.TooBig
                is PinDecision.Confirm -> {
                    val preview = withContext(Dispatchers.IO) { runCatching { request.loadPreview(context) }.getOrNull() }
                    val item = request.item
                    PinRequestUiState.Confirm(
                        appLabel = preview?.appLabel ?: labelFallback(item),
                        label = itemLabel(item),
                        icon = preview?.icon?.asImageBitmap(),
                        spanX = decision.spanX,
                        spanY = decision.spanY,
                        widget = (request as? WidgetPinRequest)?.let {
                            WidgetPinPreview(it.info, decision.spanX * cell.widthDp, decision.spanY * cell.heightDp)
                        },
                        // A recreated process still waiting for the bind dialog's answer.
                        busy = awaitingBind != null,
                    )
                }
            }
            // A recreated process with an id out for the bind dialog: answer it now if the answer came
            // first, or free the id when there is nothing left to add it to.
            val held = awaitingBind ?: return@launch
            if (decision !is PinDecision.Confirm) {
                awaitingBind = null
                withContext(NonCancellable) { pinRequestStore.releaseWidgetId(binder, held) }
            } else {
                earlyBindAnswer?.let { handleBindAnswer(it) }
            }
        }
    }

    /** A decision on the home screen as it is now, with the settings and the cell it was made with. */
    private class Decided(val decision: PinDecision, val settings: LauncherSettings, val cell: CellSizeDp)

    /**
     * Decides where (and whether) the item goes on home as it is now. A widget is measured as the
     * picker would measure it, from the workspace's cell for the grid in force — or, before the
     * workspace has laid out for this grid and screen, from an estimate of it — and on the grid it
     * will have: one that opens a new page is measured again with that page's dots (from two pages
     * on they take their row from the grid).
     */
    private suspend fun decideNow(request: PinRequest): Decided {
        val settings = settingsRepository.settings.first()
        val rows = homeLayoutRepository.homeItems.first()
        val fontScale = context.resources.configuration.fontScale
        // The page count the home screen shows for these rows (its dots are part of the chrome).
        val pages = permanentPageCount(rows.filter { it.containerId == HomeItemEntity.HOME }.mapTo(HashSet()) { it.page }, settings.homePageCount)
        // The dock shows its rows up to its columns; one it skips (no longer installed) makes
        // this count differ from the home screen's, and the estimate stand in for its cell.
        val dockIcons = minOf(rows.count { it.containerId == HomeItemEntity.DOCK }, settings.dockColumns)
        val now = workspaceChrome(settings, pages, fontScale, dockIcons)
        val decided = decideOn(request, settings, rows, now)
        val page = (decided.decision as? PinDecision.Confirm)?.page ?: return decided
        if (request !is WidgetPinRequest || page < pages) return decided
        val after = workspaceChrome(settings, page + 1, fontScale, dockIcons)
        return if (after == now) decided else decideOn(request, settings, rows, after)
    }

    /** [decideNow] on the grid with [chrome] around it. */
    private fun decideOn(
        request: PinRequest, settings: LauncherSettings, rows: List<HomeItemEntity>, chrome: WorkspaceChrome,
    ): Decided {
        val cell = cellSize.cellFor(settings.homeColumns, settings.homeRows, context.resources.configuration, chrome)
            ?: HomeCellSize.estimate(context, settings, chrome, homeWindow)
        val spans = if (request is WidgetPinRequest) requestedWidgetSpans(request.info, context, cell) else PinSpans.ICON
        val decision = decidePinRequest(
            request.item, request.isValid, settings.desktopLocked, rows, settings.homeColumns, settings.homeRows, spans,
        )
        return Decided(decision, settings, cell)
    }

    /** [decideNow] as the store takes it, inside its locks right before the accept. */
    private suspend fun placementNow(request: PinRequest): CurrentPlacement =
        decideNow(request).let { CurrentPlacement(it.decision, it.settings.homeColumns, it.settings.homeRows) }

    /** What the sheet says when the item can no longer be added as confirmed. */
    private fun refusal(decision: PinDecision): PinRequestUiState = when (decision) {
        PinDecision.Locked -> PinRequestUiState.Locked
        PinDecision.NoSpace -> PinRequestUiState.NoSpace
        PinDecision.WorkProfile -> PinRequestUiState.WorkProfile
        PinDecision.TooBig -> PinRequestUiState.TooBig
        // The request is no longer valid (accepted elsewhere, withdrawn), or nothing to refuse.
        PinDecision.Dismiss, is PinDecision.Confirm -> PinRequestUiState.Finished(failed = true)
    }

    /** "Add to home screen": accepts and stores, then closes. A second tap while under way is a no-op. */
    fun add() {
        val confirm = _uiState.value as? PinRequestUiState.Confirm ?: return
        if (confirm.busy) return
        val request = request ?: return
        _uiState.value = confirm.copy(busy = true)
        viewModelScope.launch {
            // Decided again: the lock, the grid or home itself may have changed while the sheet was open.
            val now = decideNow(request)
            if (now.decision !is PinDecision.Confirm) {
                _uiState.value = refusal(now.decision)
                return@launch
            }
            if (request is WidgetPinRequest) {
                // Finishing the activity clears this scope; an allocated id is never left half-way. What
                // becomes of the id is settled inside, where the scope's end can't skip it.
                val outcome = withContext(NonCancellable) {
                    pinRequestStore.acceptWidget(request, binder) { placementNow(request) }.also {
                        when (it) {
                            is WidgetPinOutcome.Added -> newItems.add(it.rowId)
                            // Gone while the bind call was out: nobody would open the dialog or free the id.
                            is WidgetPinOutcome.NeedsPermission ->
                                if (cleared) pinRequestStore.releaseWidgetId(binder, it.appWidgetId) else awaitingBind = it.appWidgetId
                            is WidgetPinOutcome.Refused, WidgetPinOutcome.Failed -> Unit
                        }
                    }
                }
                _uiState.value = when (outcome) {
                    is WidgetPinOutcome.Added -> PinRequestUiState.Finished(addedLabel = confirm.label)
                    is WidgetPinOutcome.NeedsPermission ->
                        confirm.copy(busy = true, bindRequest = WidgetBindRequest(outcome.appWidgetId, request.info))
                    is WidgetPinOutcome.Refused -> refusal(outcome.decision)
                    WidgetPinOutcome.Failed -> PinRequestUiState.Finished(failed = true)
                }
                return@launch
            }
            // Finishing the activity clears this scope; the accept and its row still go together.
            val outcome = withContext(NonCancellable) {
                pinRequestStore.acceptShortcut(request) { placementNow(request) }
                    .also { if (it is ShortcutPinOutcome.Added) newItems.add(it.rowId) }
            }
            _uiState.value = when (outcome) {
                is ShortcutPinOutcome.Added -> PinRequestUiState.Finished(addedLabel = confirm.label)
                is ShortcutPinOutcome.Refused -> refusal(outcome.decision)
                ShortcutPinOutcome.Failed -> PinRequestUiState.Finished(failed = true)
            }
        }
    }

    /** The screen has opened the system's bind dialog for [PinRequestUiState.Confirm.bindRequest]:
     *  a recreated screen must not open it again. The answer comes through [onBindResult]. */
    fun bindDialogOpened() {
        val confirm = _uiState.value as? PinRequestUiState.Confirm ?: return
        if (confirm.bindRequest != null) _uiState.value = confirm.copy(bindRequest = null)
    }

    /**
     * The system's bind dialog closed. Allowed: the widget is accepted with its id and stored —
     * unless the desktop was locked meanwhile. Denied or dismissed: the id is freed and the sheet
     * stays open, so the user can try again or cancel. In a recreated process the answer can come
     * before the request is handed over; it is answered once the decision is made ([attach]).
     */
    fun onBindResult(allowed: Boolean) {
        if (awaitingBind == null) return
        if (_uiState.value is PinRequestUiState.Loading) {
            earlyBindAnswer = allowed
            return
        }
        handleBindAnswer(allowed)
    }

    private fun handleBindAnswer(allowed: Boolean) {
        val appWidgetId = awaitingBind ?: return
        val confirm = _uiState.value as? PinRequestUiState.Confirm
        val request = request as? WidgetPinRequest
        viewModelScope.launch {
            // From here the id ends in a row or freed, whatever happens to the confirmation meanwhile.
            val end: PinRequestUiState = withContext(NonCancellable) {
                awaitingBind = null
                earlyBindAnswer = null
                if (!allowed || request == null) {
                    pinRequestStore.releaseWidgetId(binder, appWidgetId)
                    return@withContext confirm?.copy(busy = false, bindRequest = null) ?: PinRequestUiState.Finished()
                }
                // Decided again by the store: the lock (which wins over the app's request here too), the
                // grid or home itself may have changed while the dialog was open.
                when (val outcome = pinRequestStore.acceptBoundWidget(request, binder, appWidgetId) { placementNow(request) }) {
                    is WidgetPinOutcome.Added -> {
                        newItems.add(outcome.rowId)
                        PinRequestUiState.Finished(addedLabel = confirm?.label ?: itemLabel(request.item))
                    }
                    is WidgetPinOutcome.Refused -> refusal(outcome.decision)
                    else -> PinRequestUiState.Finished(failed = true)
                }
            }
            _uiState.value = end
        }
    }

    override fun onCleared() {
        cleared = true
        // The confirmation went away (a newer request replaced it) while the bind dialog's answer
        // was still out: the id would otherwise stay allocated until the home screen's next sweep.
        awaitingBind?.let { id ->
            awaitingBind = null
            pinRequestStore.abandonWidgetId(binder, id)
        }
    }

    /** Cancel, a swipe down, Back or a tap outside: nothing is accepted, the app hears nothing. */
    fun cancel() {
        if (_uiState.value is PinRequestUiState.Finished) return
        _uiState.value = PinRequestUiState.Finished()
    }

    private fun itemLabel(item: PinItem): String = when (item) {
        is PinItem.Shortcut -> item.label
        is PinItem.Widget -> item.label
    }

    /** The title when the requesting app's name can't be read: its package, never nothing. */
    private fun labelFallback(item: PinItem): String = when (item) {
        is PinItem.Shortcut -> item.packageName
        is PinItem.Widget -> item.provider.substringBefore('/')
    }

    private companion object {
        const val KEY_AWAITING_BIND = "pinRequest.awaitingBind"
    }
}
