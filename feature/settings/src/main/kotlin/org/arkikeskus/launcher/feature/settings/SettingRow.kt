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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import org.arkikeskus.launcher.ui.expressive.LocalExpressiveGroupRow
import org.arkikeskus.launcher.ui.expressive.LocalInExpressiveGroup
import org.arkikeskus.launcher.ui.expressive.expressiveGroupRow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import org.arkikeskus.launcher.ui.LauncherIcons

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

/** The sign of a row each home profile sets for itself, and its distance from the row's start edge: inside the
 *  card's own margin, which is 20 dp. */
private val PROFILE_SIGN = 10.dp
private val PROFILE_SIGN_INSET = 5.dp

/**
 * One row of Settings as [SettingsIndex] lists it: [content] under the row's [id], so the page and the list can be
 * held against each other. The frame adds nothing to the look, except for a moment after a search result has led
 * here: the row is scrolled into view, takes the focus (a screen reader follows it) and wears a mark that fades,
 * a ring around its card. Inside an ExpressiveGroup the row is one of the group's rows, unless [grouped] is false
 * (a row that is more than one card).
 */
@Composable
internal fun SettingRow(id: String, grouped: Boolean = true, content: @Composable () -> Unit) {
    val jumps = LocalSettingJumps.current
    // On a page that groups its rows, the group shapes this one by its place among those shown.
    val inGroup = grouped && LocalInExpressiveGroup.current
    val isTarget = jumps?.target == id
    val bringIntoView = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    val mark = remember { Animatable(0f) }
    val markColor = LocalExpressivePalette.current.accent
    // With two or more home profiles, a row each profile sets for itself wears a small sign in its start margin
    // (clear of everything written on the row) and says so to a screen reader.
    val ofProfile = LocalHomeProfileRowMarks.current && SettingsIndex.isPerProfile(id)
    val ofProfileText = stringResource(R.string.settings_profile_row_mark)
    val ofProfileSign = painterResource(LauncherIcons.Person)
    val ofProfileColor = LocalExpressivePalette.current.dim

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

    val ringWidth = MARK_RING
    // As a group's row the mark follows the outline the group gave the row: the group draws it over the row.
    val groupMark = remember(markColor) {
        fun DrawScope.(outline: Outline) {
            if (mark.value > 0f) {
                val path = Path().apply { addOutline(outline) }
                // Twice the width along the outline, cut to the outline: a ring of the width, inside the card.
                clipPath(path) {
                    drawPath(path, color = markColor.copy(alpha = mark.value), style = Stroke(width = ringWidth.toPx() * 2))
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .testTag(settingTag(id))
            .then(if (inGroup) Modifier.expressiveGroupRow(groupMark) else Modifier)
            .bringIntoViewRequester(bringIntoView)
            // Focusable only while it is the target: afterwards the row is the plain frame it was.
            .then(if (isTarget) Modifier.focusRequester(focus).focusable() else Modifier)
            .then(if (ofProfile) Modifier.semantics { contentDescription = ofProfileText } else Modifier)
            .drawWithContent {
                drawContent()
                if (ofProfile) {
                    val sign = PROFILE_SIGN.toPx()
                    val inset = PROFILE_SIGN_INSET.toPx()
                    // A row on its own ends in the gap to the next one; the sign is centred on the card.
                    val card = if (inGroup) size.height else (size.height - 10.dp.toPx()).coerceAtLeast(sign)
                    val left = if (layoutDirection == LayoutDirection.Ltr) inset else size.width - inset - sign
                    translate(left, (card - sign) / 2f) {
                        with(ofProfileSign) { draw(Size(sign, sign), colorFilter = ColorFilter.tint(ofProfileColor)) }
                    }
                }
                if (!inGroup && mark.value > 0f) {
                    // A ring along the edge of the row's card, inside its padding: nothing is drawn over what is
                    // written on the row, so the text reads as it does without the mark. The frame's last 10 dp
                    // are the gap to the next row.
                    val gap = 10.dp.toPx()
                    val ring = MARK_RING.toPx()
                    drawRoundRect(
                        color = markColor.copy(alpha = mark.value),
                        topLeft = Offset(ring / 2, ring / 2),
                        size = Size(size.width - ring, (size.height - gap - ring).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(24.dp.toPx() - ring / 2),
                        style = Stroke(width = ring),
                    )
                }
            },
    ) { CompositionLocalProvider(LocalExpressiveGroupRow provides inGroup, content = content) }
}
