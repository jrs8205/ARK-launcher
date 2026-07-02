package org.arkikeskus.launcher.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
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
private val CARD_WIDTH = 232.dp

/**
 * The Pixel-style long-press popup, shared by the home screen and the app drawer. Shows the app's
 * deep shortcuts (queried off the main thread) above the caller-supplied [actions], with a caret
 * pointing at the icon ([anchor] in root coords; [preferAbove] for dock/bottom items). Styled with
 * the shared Version C expressive palette.
 */
@Composable
fun AppActionPopup(
    app: AppItem,
    anchor: IntOffset,
    preferAbove: Boolean,
    actions: List<PopupAction>,
    onDismiss: () -> Unit,
    onPinShortcut: ((AppShortcuts.Item) -> Unit)? = null,
) {
    val context = LocalContext.current
    var shortcuts by remember(app.key) { mutableStateOf<List<AppShortcuts.Item>>(emptyList()) }
    LaunchedEffect(app.key) {
        shortcuts = withContext(Dispatchers.IO) { AppShortcuts.query(context, app) }
    }

    ExpressivePopupCard(anchor = anchor, preferAbove = preferAbove, onDismiss = onDismiss) {
        shortcuts.forEach { shortcut ->
            ShortcutPopupRow(
                text = shortcut.label,
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
        if (shortcuts.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = LocalExpressivePalette.current.trackOff,
            )
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
    ExpressivePopupCard(anchor = anchor, preferAbove = preferAbove, onDismiss = onDismiss, showCaret = false) {
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
 * the caller. [content] is the scrollable column of rows.
 */
@Composable
private fun ExpressivePopupCard(
    anchor: IntOffset,
    preferAbove: Boolean,
    onDismiss: () -> Unit,
    showCaret: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width.toFloat()
    val positionProvider = remember(anchor, preferAbove) {
        AnchoredPopupPositionProvider(anchor, preferAbove)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        ExpressiveTheme {
            val palette = LocalExpressivePalette.current
            val cardColor = palette.surfaceHi
            val cardWidthPx = with(density) { CARD_WIDTH.toPx() }
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Left-aligned caret so [caretStart] is a true offset from the card's left edge.
                if (showCaret && !preferAbove) Caret(pointingUp = true, color = cardColor, modifier = Modifier.align(Alignment.Start).padding(start = caretStart))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = cardColor,
                    tonalElevation = 3.dp,
                    shadowElevation = 10.dp,
                    modifier = Modifier.width(CARD_WIDTH),
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        content = content,
                    )
                }
                if (showCaret && preferAbove) Caret(pointingUp = false, color = cardColor, modifier = Modifier.align(Alignment.Start).padding(start = caretStart))
            }
        }
    }
}

/**
 * A shortcut row: tap the label launches it; tap the trailing **+** (shown when [onPin] != null) pins
 * it to the home screen — the + is the affordance that tells the user this shortcut can be pinned.
 */
@Composable
private fun ShortcutPopupRow(text: String, onClick: () -> Unit, onPin: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = LocalExpressivePalette.current.text,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        )
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
