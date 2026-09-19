package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test

/** Exercise the real detector and recompositions; run on an authorized emulator/device. */
class WidgetGestureTest {
    @get:Rule val compose = createComposeRule()
    private val home = HomeDragController()
    private val controller = WidgetDragController(home)

    /** A trailing advanceEventTime() moves no clock: the long-press timeout runs on the test clock. */
    private fun holdPastLongPress() {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
    }

    private fun content() {
        compose.setContent {
            Box(Modifier.size(240.dp).testTag("widget")
                .graphicsLayer { alpha = if (controller.moving) 0f else 1f }
                .widgetDragGesture("widget", true, controller) { root, fraction, owner ->
                    controller.start(WidgetDrag(EditingItem(1, null, 0, 0, 0, 2, 2),
                        null, 2, 2, fraction, null), root, owner)
                })
        }
    }

    @Test fun samePointerContinuesAfterLongPressAndRecomposition() {
        var drops = 0
        controller.onDrop = { _, _ -> drops++ }
        content()
        compose.onNodeWithTag("widget").performTouchInput { down(center) }
        holdPastLongPress()
        compose.onNodeWithTag("widget").performTouchInput {
            moveBy(Offset(40f, 60f))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("widget").performTouchInput {
            moveBy(Offset(40f, 60f))
            up()
        }
        compose.runOnIdle {
            assertThat(drops).isEqualTo(1)
            assertThat(home.localGestureActive).isFalse()
        }
    }

    @Test fun scrollingBeforeLongPressDoesNotPickUp() {
        content()
        compose.onNodeWithTag("widget").performTouchInput {
            down(center)
            advanceEventTime(30)
            moveBy(Offset(0f, 80f))
            advanceEventTime(800)
            up()
        }
        compose.runOnIdle {
            assertThat(controller.active).isNull()
            assertThat(home.localGestureActive).isFalse()
        }
    }

    @Test fun disposingSourceCancelsWithoutSaving() {
        val visible = mutableStateOf(true)
        var drops = 0
        controller.onDrop = { _, _ -> drops++ }
        compose.setContent {
            if (visible.value) Box(Modifier.size(240.dp).testTag("widget")
                .widgetDragGesture("widget", true, controller) { root, fraction, owner ->
                    controller.start(WidgetDrag(null, WidgetChoice.Builtin("battery"), 1, 1, fraction, null), root, owner)
                })
        }
        compose.onNodeWithTag("widget").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(Offset(30f, 60f))
        }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle {
            assertThat(controller.active).isNull()
            assertThat(home.localGestureActive).isFalse()
            assertThat(drops).isEqualTo(0)
        }
    }

    @Test fun aCancelledTouchNeverDrops() {
        // ACTION_CANCEL (a system gesture taking over mid-drag) reaches Compose as a synthetic,
        // already-consumed release. It must end the drag WITHOUT running the drop.
        var drops = 0
        var stills = 0
        controller.onDrop = { _, _ -> drops++ }
        controller.onStill = { stills++ }
        content()
        compose.onNodeWithTag("widget").performTouchInput { down(center) }
        holdPastLongPress()
        compose.onNodeWithTag("widget").performTouchInput { moveBy(Offset(40f, 60f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertThat(controller.moving).isTrue() }
        compose.onNodeWithTag("widget").performTouchInput { cancel() }
        compose.runOnIdle {
            assertThat(drops).isEqualTo(0)
            assertThat(stills).isEqualTo(0)
            assertThat(controller.active).isNull()
            assertThat(home.localGestureActive).isFalse()
        }
    }

    @Test fun aSecondFingerOnAnotherWidgetCannotTakeOverTheDrag() {
        val dropped = mutableListOf<Long?>()
        controller.onDrop = { drag, _ -> dropped += drag.item?.rowId }
        compose.setContent {
            Row(Modifier.testTag("row")) {
                for (rowId in 1L..2L) {
                    Box(Modifier.size(120.dp).widgetDragGesture(rowId, true, controller) { root, fraction, owner ->
                        controller.start(WidgetDrag(EditingItem(rowId, null, 0, 0, 0, 1, 1),
                            null, 1, 1, fraction, null), root, owner)
                    })
                }
            }
        }
        compose.onNodeWithTag("row").performTouchInput {
            down(0, Offset(width * 0.25f, height / 2f))
            advanceEventTime(100)
            down(1, Offset(width * 0.75f, height / 2f))
        }
        holdPastLongPress()
        compose.onNodeWithTag("row").performTouchInput {
            moveBy(0, Offset(10f, 60f))
            advanceEventTime(50)
            moveBy(0, Offset(10f, 60f))
            up(0)
            advanceEventTime(50)
            up(1)
        }
        compose.runOnIdle {
            assertThat(dropped).containsExactly(1L)
            assertThat(controller.active).isNull()
            assertThat(home.localGestureActive).isFalse()
        }
    }
}
