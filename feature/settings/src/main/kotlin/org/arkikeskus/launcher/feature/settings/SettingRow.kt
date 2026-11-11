package org.arkikeskus.launcher.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/**
 * The rows that are on screen, and the one a search result has just led to. Every [SettingRow] notes itself in
 * [shown] while it is composed, so a jump can be decided by what is really there: the row asked for, or, when the
 * setting it depends on is off, the row it falls back to.
 */
internal class SettingJumps {
    /** The ids of the rows composed right now. */
    val shown: MutableSet<String> = HashSet()

    /** The row to bring into view and mark, until it has done so. */
    var target: String? by mutableStateOf(null)

    /** [id] if its row is on screen, else its [SettingEntry.fallback] if that is; null when neither is. */
    fun resolve(id: String): String? {
        if (id in shown) return id
        val fallback = SettingsIndex.entry(id).fallback
        return fallback?.takeIf { it in shown }
    }
}

/** The jumps of the Settings screen the row is part of; null where a row is shown on its own (tests of a row). */
internal val LocalSettingJumps = staticCompositionLocalOf<SettingJumps?> { null }

/** How long the mark on a row a search led to stays before it has faded away. */
private const val MARK_HOLD_MS = 250
private const val MARK_FADE_MS = 1250

/** The width of the mark: a ring in the accent along the edge of the row's card. */
private val MARK_RING = 3.dp

/**
 * One row of Settings as [SettingsIndex] lists it: [content] under the row's [id], so the page and the list can be
 * held against each other. The frame adds nothing to the look, except for a moment after a search result has led
 * here: the row is scrolled into view, takes the focus (a screen reader follows it) and wears a mark that fades,
 * a ring around its card.
 */
@Composable
internal fun SettingRow(id: String, content: @Composable () -> Unit) {
    val jumps = LocalSettingJumps.current
    val isTarget = jumps?.target == id
    val bringIntoView = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    val mark = remember { Animatable(0f) }
    val markColor = LocalExpressivePalette.current.accent

    if (jumps != null) {
        DisposableEffect(jumps, id) {
            jumps.shown += id
            onDispose { jumps.shown -= id }
        }
        LaunchedEffect(isTarget) {
            if (!isTarget) {
                // However the jump to this row ended (a newer one took its place while the mark was still on),
                // a row that is not the target wears no mark.
                if (mark.value != 0f) mark.snapTo(0f)
                return@LaunchedEffect
            }
            try {
                // Marked and focused at once; the scroll to it takes a moment, and the mark fades after it has arrived.
                mark.snapTo(1f)
                runCatching { focus.requestFocus() }
                // The scroll is a job of its own: when it is cut short (a finger takes the page over, the container
                // cancels the request) only the scroll ends, and the mark still fades.
                launch { bringIntoView.bringIntoView() }.join()
                mark.animateTo(0f, tween(durationMillis = MARK_FADE_MS, delayMillis = MARK_HOLD_MS))
            } finally {
                // Also when the page is left before the mark has faded: a jump happens once, and one cut short
                // must not wait for the row's next visit. A newer jump to another row is left alone.
                if (jumps.target == id) jumps.target = null
            }
        }
    }

    Column(
        modifier = Modifier
            .testTag(settingTag(id))
            .bringIntoViewRequester(bringIntoView)
            // Focusable only while it is the target: afterwards the row is the plain frame it was.
            .then(if (isTarget) Modifier.focusRequester(focus).focusable() else Modifier)
            .drawWithContent {
                drawContent()
                if (mark.value > 0f) {
                    // A ring along the edge of the row's card, inside its padding: nothing is drawn over what is
                    // written on the row, so the text reads as it does without the mark. The frame's last 10 dp
                    // are the gap to the next row.
                    val gap = 10.dp.toPx()
                    val ring = MARK_RING.toPx()
                    drawRoundRect(
                        color = markColor.copy(alpha = mark.value),
                        topLeft = Offset(ring / 2, ring / 2),
                        size = Size(size.width - ring, (size.height - gap - ring).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(20.dp.toPx() - ring / 2),
                        style = Stroke(width = ring),
                    )
                }
            },
    ) { content() }
}
