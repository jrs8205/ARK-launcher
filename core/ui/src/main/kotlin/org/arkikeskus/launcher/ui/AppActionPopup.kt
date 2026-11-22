package org.arkikeskus.launcher.ui

import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.ExpressiveTheme
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import androidx.compose.foundation.background
import org.arkikeskus.launcher.ui.expressive.ExpressiveGroup
import org.arkikeskus.launcher.ui.expressive.END_GAP
import org.arkikeskus.launcher.ui.expressive.ExpressiveGroupBreak
import org.arkikeskus.launcher.ui.expressive.ROW_GAP
import org.arkikeskus.launcher.ui.expressive.GroupPosition
import org.arkikeskus.launcher.ui.expressive.expressiveGroupRow
import org.arkikeskus.launcher.ui.expressive.groupShape

/**
 * One action row in the long-press popup (e.g. "App info", "Remove from home"); optional leading icon.
 * [keptWhenLocked] marks a row that changes nothing on the desktop (the app's page in Android's settings, its
 * uninstall): it is the only kind a locked desktop's popup shows. A row is left out there unless it says so.
 */
data class PopupAction(
    val label: String,
    @DrawableRes val icon: Int? = null,
    val keptWhenLocked: Boolean = false,
    val onClick: () -> Unit,
)

/** One icon+label row in a generic [IconMenuPopup] (e.g. "Launcher", "Wallpaper"). */
data class IconMenuItem(@DrawableRes val icon: Int, val label: String, val onClick: () -> Unit)

private const val POPUP_MARGIN_PX = 24

/** The most the card waits for its shortcut and Widgets rows before showing with what has arrived. */
internal const val POPUP_ROWS_WAIT_MS = 400L
private val CARD_WIDTH = 232.dp
internal val SHORTCUT_ICON_SIZE = 24.dp

/** The most of the window's height a menu's rows take; more of them scroll. */
private val POPUP_ROWS_MAX_HEIGHT = 520.dp
private val CARET_HEIGHT = 10.dp

/** An action row's side padding, its icon and the room between the icon and the label: what is left of the
 *  card's width is the label's, which the count of the rows' heights measures it in. */
private val POPUP_ROW_SIDE = 20.dp
private val POPUP_ROW_ICON = 20.dp
private val POPUP_ROW_ICON_GAP = 16.dp

/**
 * True where a menu's rows are drawn closer: the rows at their usual height would not fit the menu's room, and
 * a menu that has to be scrolled hides its last rows (issue #30, a follow-up). Read by the rows themselves.
 */
internal val LocalPopupRowsCompact = compositionLocalOf { false }

/** The least height of a row: a full touch target either way (48 dp, and WCAG's 44 dp for the compact one). */
private fun popupRowMinHeight(compact: Boolean): Dp = if (compact) 44.dp else 48.dp

/** The room over and under a row's text. */
private fun popupRowPadding(compact: Boolean): Dp = if (compact) 10.dp else 12.dp

/** How tall a row is whose text is [text] tall (one line, or more where a label wraps). */
internal fun popupRowHeight(text: Dp, compact: Boolean): Dp =
    maxOf(popupRowMinHeight(compact), text + popupRowPadding(compact) * 2)

/** How tall rows are together whose texts are [texts] tall, one height per row, in [groups] groups: with the
 *  gaps a grouped list puts between them. */
internal fun popupRowsHeight(texts: List<Dp>, groups: Int, compact: Boolean): Dp =
    if (texts.isEmpty()) {
        0.dp
    } else {
        texts.fold(0.dp) { sum, text -> sum + popupRowHeight(text, compact) } +
            ROW_GAP * (texts.size - groups) + END_GAP * (groups - 1).coerceAtLeast(0)
    }

/** Whether a menu draws its rows closer: only when they are taller than its [room] at their usual height. */
internal fun popupRowsCompact(texts: List<Dp>, groups: Int, room: Dp): Boolean =
    popupRowsHeight(texts, groups, compact = false) > room

/** How wide a folder's card is, in the app drawer and on the home screen (the window less 16 dp at most). */
val FOLDER_CARD_WIDTH = 328.dp

/**
 * The Pixel-style long-press popup, shared by the home screen and the app drawer. Shows the app's
 * deep shortcuts (queried off the main thread) above a Widgets row — when [onWidgets] is given and
 * [hasWidgets] finds the app home-screen widgets, as in Pixel Launcher — and the caller-supplied
 * [actions], with a caret pointing at the icon ([anchor] in root coords; [preferAbove] for
 * dock/bottom items). Styled with the shared Version C expressive palette.
 *
 * The card appears once both queries have answered (Launcher3 counts the rows before it shows):
 * a card that grew as the rows arrived jumped on screen whenever it was anchored above its icon
 * (issue #21). A query that is still out after [POPUP_ROWS_WAIT_MS] no longer holds the card.
 *
 * [held] is true while the finger that lifted the icon is still down — the menu opens at the lift
 * (issue #12). A second finger's tap on a row does nothing then: the row would act on an icon that is
 * still being held. Once the finger lifts the menu works as usual.
 *
 * On a [locked] desktop the popup still opens, with what changes nothing there: the app's shortcuts (to start,
 * not to pin) and the [actions] marked [PopupAction.keptWhenLocked]; no Widgets row.
 */
@Composable
fun AppActionPopup(
    app: AppItem,
    anchor: IntOffset,
    preferAbove: Boolean,
    actions: List<PopupAction>,
    onDismiss: () -> Unit,
    onPinShortcut: ((AppShortcuts.Item) -> Unit)? = null,
    onWidgets: (() -> Unit)? = null,
    hasWidgets: (suspend (AppItem) -> Boolean)? = null,
    /** The app's shortcuts for the menu; null = asked of Android here, off the main thread. */
    shortcutsOf: (suspend (AppItem) -> List<AppShortcuts.Item>)? = null,
    held: Boolean = false,
    locked: Boolean = false,
    /** The palette of the surface the menu opens from when that is not the theme root's (the app drawer with the
     *  user's own colour); null = the theme root's. */
    palette: ExpressivePalette? = null,
) {
    val context = LocalContext.current
    val background = LocalBackgroundDispatcher.current
    // The lock is asked here, once for every surface that opens this popup: what adds to the desktop (a
    // shortcut's pin, the Widgets row) or changes it (a row not marked as kept) is not offered.
    @Suppress("NAME_SHADOWING") val onPinShortcut = onPinShortcut.takeUnless { locked }
    @Suppress("NAME_SHADOWING") val onWidgets = onWidgets.takeUnless { locked }
    @Suppress("NAME_SHADOWING") val actions = if (locked) actions.filter { it.keptWhenLocked } else actions
    // Null = still being asked for; the card waits for both (or the wait below) before it shows.
    var shortcuts by remember(app.key) { mutableStateOf<List<AppShortcuts.Item>?>(null) }
    LaunchedEffect(app.key) {
        shortcuts = shortcutsOf?.invoke(app) ?: withContext(background) { AppShortcuts.query(context, app) }
    }
    var widgets by remember(app.key) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(app.key, onWidgets != null) {
        widgets = onWidgets != null && (
            hasWidgets?.invoke(app) ?: withContext(background) { AppWidgets.hasHomeScreenWidgets(context, app) }
        )
    }
    var waited by remember(app.key) { mutableStateOf(false) }
    LaunchedEffect(app.key) {
        delay(POPUP_ROWS_WAIT_MS)
        waited = true
    }
    if (!waited && (shortcuts == null || widgets == null)) {
        // No card yet, so no popup window to take BACK: without this the press fell through to the
        // host and the menu still opened once the rows arrived.
        BackHandler { onDismiss() }
        return
    }

    // Counted before the card shows (the rows are known by now), so the card never changes its rows on screen.
    val showsWidgets = widgets == true && onWidgets != null
    val shortcutRows = shortcuts.orEmpty().size
    val otherRows = actions.size + if (showsWidgets) 1 else 0
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
    val rowStyle = MaterialTheme.typography.bodyLarge
    val measurer = rememberTextMeasurer()
    val widgetsLabel = stringResource(R.string.popup_widgets)
    val compact = with(density) {
        val line = (if (lineHeight.isSp) lineHeight else 24.sp).toDp()
        // A row's text as tall as it is drawn: a shortcut's is one line (it never wraps), an action's label
        // wraps in the width its row leaves it, and a wrapped one is a line taller. Counted as one line each,
        // a menu with a long label was taken to fit and its last row was cut.
        fun labelHeight(label: String, hasIcon: Boolean): Dp {
            // The menu is as wide as its window where that is narrower than the menu (a split screen).
            val card = minOf(CARD_WIDTH, windowWidthPx.toDp())
            val width = card - POPUP_ROW_SIDE * 2 - if (hasIcon) POPUP_ROW_ICON + POPUP_ROW_ICON_GAP else 0.dp
            return measurer.measure(label, rowStyle, constraints = Constraints(maxWidth = width.roundToPx())).size.height.toDp()
        }
        val texts = List(shortcutRows) { line } +
            (if (showsWidgets) listOf(labelHeight(widgetsLabel, hasIcon = true)) else emptyList()) +
            actions.map { labelHeight(it.label, hasIcon = it.icon != null) }
        popupRowsCompact(
            texts = texts,
            groups = (if (shortcutRows > 0) 1 else 0) + (if (otherRows > 0) 1 else 0),
            // What the window leaves between its margins beside the caret, and never more than the menu's own cap.
            room = minOf(POPUP_ROWS_MAX_HEIGHT, (windowHeightPx - 2 * POPUP_MARGIN_PX).toDp() - CARET_HEIGHT),
        )
    }

    CompositionLocalProvider(LocalPopupRowsCompact provides compact) { AnchoredPopupMenu(
        anchor = anchor, preferAbove = preferAbove, onDismiss = onDismiss, blockTouches = held, palette = palette,
    ) {
        shortcuts.orEmpty().forEach { shortcut ->
            ShortcutPopupRow(
                label = shortcut.label,
                shortLabel = shortcut.shortLabel,
                icon = shortcut.icon,
                onClick = {
                    AppShortcuts.start(context, shortcut)
                    onDismiss()
                },
                onPin = onPinShortcut?.let {
                    {
                        it(shortcut)
                        onDismiss()
                    }
                },
            )
        }
        // The app's shortcuts are a group of their own above the launcher's actions.
        if (!shortcuts.isNullOrEmpty()) ExpressiveGroupBreak()
        if (showsWidgets) {
            PopupMenuRow(widgetsLabel, LauncherIcons.Widgets) {
                onWidgets?.invoke()
                onDismiss()
            }
        }
        actions.forEach { action ->
            PopupMenuRow(action.label, action.icon) {
                action.onClick()
                onDismiss()
            }
        }
    } }
}

/**
 * A generic icon+label popup with the same Version C card/caret/positioning as [AppActionPopup].
 * Used by the home empty-area long-press menu (Launcher / Wallpaper / …).
 */
@Composable
fun IconMenuPopup(
    anchor: IntOffset,
    preferAbove: Boolean,
    items: List<IconMenuItem>,
    onDismiss: () -> Unit,
    /** As in [AppActionPopup]: the opening surface's own palette, null = the theme root's. */
    palette: ExpressivePalette? = null,
) {
    AnchoredPopupMenu(
        anchor = anchor, preferAbove = preferAbove, onDismiss = onDismiss, showCaret = false, palette = palette,
    ) {
        items.forEach { item ->
            PopupMenuRow(item.label, item.icon) {
                item.onClick()
                onDismiss()
            }
        }
    }
}

/**
 * Shared Version C popup chrome: an anchored, dismissible rounded card (with a caret pointing at the
 * icon) wrapped in [ExpressiveTheme] so it resolves the warm-orange dark/light palette regardless of
 * the caller. [content] is the scrollable column of rows, [width] the card's width (the menus' by
 * default; a drawer folder's card is wider); while [blockTouches] a cover over the card takes every
 * touch, so no row gets one.
 *
 * Both menus can open while a finger is still down on the launcher. Android 11–12L send a second
 * finger to the window the first is in unless the window it lands on splits touches, so without
 * [FLAG_SPLIT_TOUCH] a tap on a row reached the icons behind the menu instead.
 */
@Composable
fun AnchoredPopupCard(
    anchor: IntOffset,
    preferAbove: Boolean,
    onDismiss: () -> Unit,
    showCaret: Boolean = true,
    blockTouches: Boolean = false,
    width: Dp = CARD_WIDTH,
    /** The palette the card and its content take: the caller's own (the drawer's, following the user's colour);
     *  null = the theme root's base colour, which reaches the popup's window by itself. */
    palette: ExpressivePalette? = null,
    /** False for a content that scrolls a part of itself (a folder's apps under its title): the card then does
     *  not scroll as a whole, and its content is given the card's height to share out. */
    scrolls: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnchoredPopupWindow(anchor, preferAbove, onDismiss, palette) { windowWidthPx ->
        PopupCardContent(anchor, preferAbove, windowWidthPx, showCaret, blockTouches, width, scrolls, content)
    }
}

/**
 * A long-press menu: the same anchored window as [AnchoredPopupCard], but its [rows] (each a [PopupMenuRow] or a
 * [ShortcutPopupRow]) form a grouped list in the container colour instead of sitting on one card.
 */
@Composable
internal fun AnchoredPopupMenu(
    anchor: IntOffset,
    preferAbove: Boolean,
    onDismiss: () -> Unit,
    showCaret: Boolean = true,
    blockTouches: Boolean = false,
    palette: ExpressivePalette? = null,
    rows: @Composable () -> Unit,
) {
    AnchoredPopupWindow(anchor, preferAbove, onDismiss, palette) { windowWidthPx ->
        PopupMenuContent(anchor, preferAbove, windowWidthPx, showCaret, blockTouches, rows)
    }
}

/**
 * A card like [AnchoredPopupCard]'s, its caret and its place by [anchor], for a card that has to stay in the
 * window it is opened from: the home screen's open folder, whose icons are dragged on over the home screen
 * (pointer events never cross windows). It fills its parent, which fills the window ([anchor] is in the window's
 * root coordinates, as for the popups), and takes no touches itself. [body] is the one card, as wide as the
 * caller makes it, in the colour the caret takes ([caretColor]).
 *
 * The card has the room on its side of the anchor, so it does not lie over what it opened from, and a taller
 * content scrolls inside it. Where that room is less than the least the card needs ([body]'s min intrinsic height:
 * what of it does not scroll, and as much of what does as it must show), the card has the window's height between
 * the bars instead. It then lies over what it opened from and has no caret, which would point at nothing. Cut to
 * the room, a folder's editor in the middle rows of a low window showed no app and a sliver of its last button.
 */
@Composable
fun AnchoredCardInPlace(
    anchor: IntOffset,
    preferAbove: Boolean,
    caretColor: Color,
    modifier: Modifier = Modifier,
    /** Pixels at the parent's top and bottom the card keeps out of (the system bars over the home screen). */
    safeTop: Int = 0,
    safeBottom: Int = 0,
    body: @Composable () -> Unit,
) {
    Layout(
        contents = listOf<@Composable () -> Unit>(
            body,
            { Caret(pointingUp = !preferAbove, color = caretColor, modifier = Modifier.testTag(POPUP_CARET_TAG)) },
        ),
        modifier = modifier,
    ) { (bodies, carets), constraints ->
        val margin = POPUP_MARGIN_PX
        val cardMeasurable = bodies.single()
        val caret = carets.single().measure(Constraints())
        val room = if (preferAbove) {
            anchor.y - margin - (safeTop + margin)
        } else {
            constraints.maxHeight - safeBottom - margin - (anchor.y + margin)
        }
        val onItsSide = cardMeasurable.minIntrinsicHeight(constraints.maxWidth) + caret.height <= room
        val maxHeight = if (onItsSide) room - caret.height else constraints.maxHeight - safeTop - safeBottom - 2 * margin
        val card = cardMeasurable.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = maxHeight.coerceAtLeast(0)))
        val frame = IntSize(card.width, card.height + if (onItsSide) caret.height else 0)
        val parent = IntSize(constraints.maxWidth, constraints.maxHeight)
        val at = anchoredPopupPosition(anchor, preferAbove, parent, frame, safeTop, safeBottom)
        val caretLeft = caretLeftPx(anchor.x.toFloat(), at.x.toFloat(), card.width.toFloat(), caret.width.toFloat()).roundToInt()
        // Absolute, like the anchor: a right-to-left layout does not mirror where the icon is. A caret that is
        // not placed is not drawn.
        layout(parent.width, parent.height) {
            when {
                !onItsSide -> card.place(at)
                preferAbove -> {
                    card.place(at)
                    caret.place(at.x + caretLeft, at.y + card.height)
                }
                else -> {
                    caret.place(at.x + caretLeft, at.y)
                    card.place(at.x, at.y + caret.height)
                }
            }
        }
    }
}

/** The test tag of a card's caret. */
private const val POPUP_CARET_TAG = "popup-caret"

/** Where the caret begins from its card's left edge ([cardLeft]), in pixels: under [anchorX], clear of the corners. */
private fun caretLeftPx(anchorX: Float, cardLeft: Float, cardWidthPx: Float, caretWidthPx: Float): Float =
    ((anchorX - cardLeft) - caretWidthPx / 2f).coerceIn(14f, cardWidthPx - caretWidthPx - 14f)

/** The window both share: anchored, dismissed by a touch outside, splitting touches, in [palette] or the root's. */
@Composable
private fun AnchoredPopupWindow(
    anchor: IntOffset,
    preferAbove: Boolean,
    onDismiss: () -> Unit,
    palette: ExpressivePalette?,
    body: @Composable (windowWidthPx: Float) -> Unit,
) {
    val windowWidthPx = LocalWindowInfo.current.containerSize.width.toFloat()
    val positionProvider = remember(anchor, preferAbove) {
        AnchoredPopupPositionProvider(anchor, preferAbove)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        // = PopupProperties(focusable = true), plus split touch.
        properties = PopupProperties(flags = FLAG_WATCH_OUTSIDE_TOUCH or FLAG_SPLIT_TOUCH),
    ) {
        if (palette != null) {
            CompositionLocalProvider(LocalExpressivePalette provides palette) { body(windowWidthPx) }
        } else {
            ExpressiveTheme { body(windowWidthPx) }
        }
    }
}

/**
 * The card of [AnchoredPopupCard] without its window: the caret, the rows and the touch cover.
 * The caret sits [anchor]'s distance from the card's LEFT edge whatever the layout direction: under
 * a right-to-left system language (the drawer is not locked to LTR) a start-aligned caret mirrored
 * to the other side of the card, away from the icon.
 */
@Composable
internal fun PopupCardContent(
    anchor: IntOffset,
    preferAbove: Boolean,
    windowWidthPx: Float,
    showCaret: Boolean = true,
    blockTouches: Boolean = false,
    width: Dp = CARD_WIDTH,
    scrolls: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardColor = LocalExpressivePalette.current.surfaceHi
    PopupFrame(anchor, preferAbove, windowWidthPx, showCaret, blockTouches, width, caretColor = cardColor) {
        Surface(
            shape = groupShape(GroupPosition.Only),
            color = cardColor,
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.width(width),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(vertical = 8.dp),
                content = content,
            )
        }
    }
}

/** The rows of [AnchoredPopupMenu] without their window: a grouped list in the container colour, 2 dp apart. */
@Composable
internal fun PopupMenuContent(
    anchor: IntOffset,
    preferAbove: Boolean,
    windowWidthPx: Float,
    showCaret: Boolean = true,
    blockTouches: Boolean = false,
    rows: @Composable () -> Unit,
) {
    PopupFrame(
        anchor, preferAbove, windowWidthPx, showCaret, blockTouches, CARD_WIDTH,
        caretColor = LocalExpressivePalette.current.container,
    ) {
        ExpressiveGroup(
            modifier = Modifier.width(CARD_WIDTH).heightIn(max = POPUP_ROWS_MAX_HEIGHT).verticalScroll(rememberScrollState()),
            // The caret sits against the last row; the rows float over the home screen and need the menu's shadow.
            trailingGap = false,
            shadow = 10.dp,
            content = rows,
        )
    }
}

/** What both popups share: the caret above or below [body], and the cover that takes every touch. */
@Composable
private fun PopupFrame(
    anchor: IntOffset,
    preferAbove: Boolean,
    windowWidthPx: Float,
    showCaret: Boolean,
    blockTouches: Boolean,
    width: Dp,
    caretColor: Color,
    body: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cardWidthPx = with(density) { width.toPx() }
    val caretWidthPx = with(density) { 22.dp.toPx() }
    val cardLeft = (anchor.x - cardWidthPx / 2f).coerceIn(
        POPUP_MARGIN_PX.toFloat(),
        (windowWidthPx - cardWidthPx - POPUP_MARGIN_PX).coerceAtLeast(POPUP_MARGIN_PX.toFloat()),
    )
    val caretStart = with(density) { caretLeftPx(anchor.x.toFloat(), cardLeft, cardWidthPx, caretWidthPx).toDp() }
    Box {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showCaret && !preferAbove) Caret(pointingUp = true, color = caretColor, modifier = caretModifier(caretStart))
            // Weighted, so the caret is measured first: a body as tall as the window it is given would
            // otherwise leave a caret under it no height at all.
            Box(Modifier.weight(1f, fill = false)) { body() }
            if (showCaret && preferAbove) Caret(pointingUp = false, color = caretColor, modifier = caretModifier(caretStart))
        }
        // On top of the rows, so it is hit instead of them (siblings do not share touches).
        if (blockTouches) {
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
            )
        }
    }
}

/** The caret [caretStart] from the card's LEFT edge, absolute: a layout direction never mirrors it. */
private fun ColumnScope.caretModifier(caretStart: Dp): Modifier =
    Modifier.align(AbsoluteAlignment.Left).absolutePadding(left = caretStart).testTag(POPUP_CARET_TAG)

/**
 * A shortcut row: the shortcut's own icon, then [label] when it fits on one line, else [shortLabel]
 * (Launcher3's `DeepShortcutView` rule); tapping either launches it. The trailing **+** (shown when
 * [onPin] != null) pins it to the home screen — the affordance that tells the user it can be pinned.
 */
@Composable
internal fun ShortcutPopupRow(
    label: String,
    shortLabel: String,
    icon: ImageBitmap?,
    onClick: () -> Unit,
    onPin: (() -> Unit)?,
) {
    val palette = LocalExpressivePalette.current
    val compact = LocalPopupRowsCompact.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .expressiveGroupRow()
            .background(palette.container)
            .heightIn(min = popupRowMinHeight(compact)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                // Tighter than an action row: the long label should fit as often as on Pixel.
                .padding(
                    start = 16.dp, end = if (onPin == null) 16.dp else 0.dp,
                    top = popupRowPadding(compact), bottom = popupRowPadding(compact),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The slot stays when an icon is missing, so the labels of one popup line up.
            Box(Modifier.size(SHORTCUT_ICON_SIZE)) {
                if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            val style = MaterialTheme.typography.bodyLarge
            BoxWithConstraints(Modifier.weight(1f).padding(start = 12.dp)) {
                val measurer = rememberTextMeasurer()
                val maxWidthPx = constraints.maxWidth
                val shown = remember(measurer, label, shortLabel, style, maxWidthPx) {
                    val fits = measurer.measure(label, style, softWrap = false, maxLines = 1).size.width <= maxWidthPx
                    if (fits) label else shortLabel
                }
                Text(
                    text = shown,
                    style = style,
                    color = palette.onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onPin != null) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPin),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(LauncherIcons.Add),
                    contentDescription = stringResource(R.string.shortcut_pin),
                    tint = palette.onContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** One row of a long-press menu: a row of the menu's group, in the container colour, at least 48 dp high. */
@Composable
internal fun PopupMenuRow(text: String, @DrawableRes icon: Int?, onClick: () -> Unit) {
    val palette = LocalExpressivePalette.current
    val compact = LocalPopupRowsCompact.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .expressiveGroupRow()
            .background(palette.container)
            .clickable(onClick = onClick)
            .heightIn(min = popupRowMinHeight(compact))
            .padding(horizontal = POPUP_ROW_SIDE, vertical = popupRowPadding(compact)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = palette.onContainer,
                modifier = Modifier.size(POPUP_ROW_ICON),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.onContainer,
            modifier = Modifier.padding(start = if (icon != null) POPUP_ROW_ICON_GAP else 0.dp),
        )
    }
}

/** Small triangle that visually ties the popup to the icon it acts on. */
@Composable
private fun Caret(pointingUp: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 22.dp, height = 10.dp)) {
        val path = Path().apply {
            if (pointingUp) {
                moveTo(0f, size.height)
                lineTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width / 2f, size.height)
                lineTo(size.width, 0f)
            }
            close()
        }
        drawPath(path, color)
    }
}

/** Places the menu centered under (or above) the icon, clamped to the screen. */
private class AnchoredPopupPositionProvider(
    private val anchor: IntOffset,
    private val preferAbove: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = anchoredPopupPosition(anchor, preferAbove, windowSize, popupContentSize)
}

/**
 * Where an anchored card of [contentSize] goes in a window of [windowSize]: centred on [anchor]'s x and a margin
 * under it, or with [preferAbove] a margin over it, and always a margin inside the window, whose top
 * [safeTop] and bottom [safeBottom] pixels it keeps out of as well.
 */
internal fun anchoredPopupPosition(
    anchor: IntOffset,
    preferAbove: Boolean,
    windowSize: IntSize,
    contentSize: IntSize,
    safeTop: Int = 0,
    safeBottom: Int = 0,
): IntOffset {
    val margin = POPUP_MARGIN_PX
    val x = (anchor.x - contentSize.width / 2)
        .coerceIn(margin, (windowSize.width - contentSize.width - margin).coerceAtLeast(margin))
    val top = safeTop + margin
    val lowest = (windowSize.height - safeBottom - contentSize.height - margin).coerceAtLeast(top)
    val y = if (preferAbove) anchor.y - contentSize.height - margin else anchor.y + margin
    return IntOffset(x, y.coerceIn(top, lowest))
}
