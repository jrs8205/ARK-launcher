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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.ExpressiveTheme
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** One action row in the long-press popup (e.g. "App info", "Remove from home"); optional leading icon. */
data class PopupAction(val label: String, @DrawableRes val icon: Int? = null, val onClick: () -> Unit)

/** One icon+label row in a generic [IconMenuPopup] (e.g. "Launcher", "Wallpaper"). */
data class IconMenuItem(@DrawableRes val icon: Int, val label: String, val onClick: () -> Unit)

private const val POPUP_MARGIN_PX = 24

/** The most the card waits for its shortcut and Widgets rows before showing with what has arrived. */
internal const val POPUP_ROWS_WAIT_MS = 400L
private val CARD_WIDTH = 232.dp
internal val SHORTCUT_ICON_SIZE = 24.dp

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
    held: Boolean = false,
) {
    val context = LocalContext.current
    // Null = still being asked for; the card waits for both (or the wait below) before it shows.
    var shortcuts by remember(app.key) { mutableStateOf<List<AppShortcuts.Item>?>(null) }
    LaunchedEffect(app.key) {
        shortcuts = withContext(Dispatchers.IO) { AppShortcuts.query(context, app) }
    }
    var widgets by remember(app.key) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(app.key, onWidgets != null) {
        widgets = onWidgets != null && (
            hasWidgets?.invoke(app) ?: withContext(Dispatchers.IO) { AppWidgets.hasHomeScreenWidgets(context, app) }
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

    AnchoredPopupCard(anchor = anchor, preferAbove = preferAbove, onDismiss = onDismiss, blockTouches = held) {
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
        if (!shortcuts.isNullOrEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = LocalExpressivePalette.current.trackOff,
            )
        }
        if (widgets == true && onWidgets != null) {
            PopupRow(stringResource(R.string.popup_widgets), LauncherIcons.Widgets) {
                onWidgets()
                onDismiss()
            }
        }
        actions.forEach { action ->
            PopupRow(action.label, action.icon) {
                action.onClick()
                onDismiss()
            }
        }
    }
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
) {
    AnchoredPopupCard(anchor = anchor, preferAbove = preferAbove, onDismiss = onDismiss, showCaret = false) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        item.onClick()
                        onDismiss()
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(item.icon),
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalExpressivePalette.current.text,
                    modifier = Modifier.padding(start = 16.dp),
                )
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
    content: @Composable ColumnScope.() -> Unit,
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
        ExpressiveTheme {
            PopupCardContent(anchor, preferAbove, windowWidthPx, showCaret, blockTouches, width, content)
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val palette = LocalExpressivePalette.current
    val cardColor = palette.surfaceHi
    val cardWidthPx = with(density) { width.toPx() }
    val caretWidthPx = with(density) { 22.dp.toPx() }
    val cardLeft = (anchor.x - cardWidthPx / 2f).coerceIn(
        POPUP_MARGIN_PX.toFloat(),
        (windowWidthPx - cardWidthPx - POPUP_MARGIN_PX).coerceAtLeast(POPUP_MARGIN_PX.toFloat()),
    )
    val caretStart = with(density) {
        ((anchor.x - cardLeft) - caretWidthPx / 2f)
            .coerceIn(14f, cardWidthPx - caretWidthPx - 14f)
            .toDp()
    }
    Box {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showCaret && !preferAbove) Caret(pointingUp = true, color = cardColor, modifier = caretModifier(caretStart))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardColor,
                tonalElevation = 3.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.width(width),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    content = content,
                )
            }
            if (showCaret && preferAbove) Caret(pointingUp = false, color = cardColor, modifier = caretModifier(caretStart))
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
    Modifier.align(AbsoluteAlignment.Left).absolutePadding(left = caretStart).testTag("popup-caret")

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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                // Tighter than an action row: the long label should fit as often as on Pixel.
                .padding(start = 16.dp, end = if (onPin == null) 16.dp else 0.dp, top = 12.dp, bottom = 12.dp),
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
                    color = LocalExpressivePalette.current.text,
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
                    tint = Accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun PopupRow(text: String, @DrawableRes icon: Int?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = LocalExpressivePalette.current.text,
            modifier = Modifier.padding(start = if (icon != null) 16.dp else 0.dp),
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
    ): IntOffset {
        val margin = POPUP_MARGIN_PX
        val x = (anchor.x - popupContentSize.width / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = if (preferAbove) {
            (anchor.y - popupContentSize.height - margin).coerceAtLeast(margin)
        } else {
            (anchor.y + margin)
                .coerceAtMost((windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        }
        return IntOffset(x, y)
    }
}
