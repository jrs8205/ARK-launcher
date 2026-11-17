package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Focus and a button's keys for a tile whose touches are a pointer gesture of its own (a clickable would take
 * that gesture's events): a keyboard or a D-pad stops on the tile and shows it, Enter, the D-pad's centre or
 * Space is [onClick], and the key held for the long-press time is [onLongClick], after which its release is no
 * click. What a clickable gives a button, without its pointer input.
 */
@Composable
internal fun Modifier.keyClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val holdMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val press = remember { KeyPress() }
    return this
        // Focus gone with the key still down (the hold opened a window of its own): its release is not ours.
        .onFocusChanged { if (!it.isFocused) press.end() }
        .onKeyEvent { event ->
            if (!event.key.isButtonKey) return@onKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    // A held key repeats its down: only the first one starts the hold.
                    if (press.hold == null) {
                        press.held = false
                        press.hold = scope.launch {
                            delay(holdMillis)
                            press.held = true
                            currentOnLongClick()
                        }
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    val pressed = press.hold != null
                    val held = press.held
                    press.end()
                    if (pressed && !held) currentOnClick()
                    pressed
                }
                else -> false
            }
        }
        .focusable(interactionSource = interactions)
        .indication(interactions, LocalIndication.current)
}

/** The keys that press a button. */
private val Key.isButtonKey: Boolean
    get() = this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter || this == Key.Spacebar

/** One key press on a tile: the timer of its hold, and whether the hold has fired. */
private class KeyPress {
    var hold: Job? = null
    var held = false

    fun end() {
        hold?.cancel()
        hold = null
        held = false
    }
}
