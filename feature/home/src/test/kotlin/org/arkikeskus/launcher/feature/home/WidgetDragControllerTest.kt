package org.arkikeskus.launcher.feature.home

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.WidgetPlacement
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Test

class WidgetDragControllerTest {
    private val home = HomeDragController()
    private val controller = WidgetDragController(home)
    private val drag = WidgetDrag(EditingItem(1, null, 0, 0, 0, 2, 2), null, 2, 2, Offset(.5f, .5f), null)

    @Test fun holdThenMoveAndReleaseCommitsOneGesture() {
        var drops = 0
        var edits = 0
        controller.onDrop = { _, _ -> drops++ }
        controller.onStill = { edits++ }
        controller.start(drag, Offset(100f, 100f))
        assertThat(home.localGestureActive).isTrue()
        controller.move(Offset(200f, 200f))
        controller.finish()
        controller.finish()
        assertThat(drops).isEqualTo(1)
        assertThat(edits).isEqualTo(0)
        assertThat(home.localDragging).isFalse()
        assertThat(home.localGestureActive).isFalse()
    }

    @Test fun stationaryHoldOpensEditWithoutWriting() {
        var edits = 0
        controller.onStill = { edits++ }
        controller.onDrop = { _, _ -> error("Unexpected drop") }
        controller.start(drag, Offset.Zero)
        controller.finish()
        assertThat(edits).isEqualTo(1)
    }

    @Test fun cancelDoesNotSaveOrLeaveGestureFlags() {
        controller.onDrop = { _, _ -> error("Cancelled drag must not save") }
        controller.onStill = { error("Cancelled hold must not open an editor") }
        controller.start(drag, Offset.Zero)
        controller.move(Offset(50f, 50f))
        controller.cancel()
        controller.finish()
        assertThat(controller.active).isNull()
        assertThat(home.localDragging).isFalse()
        assertThat(home.localGestureActive).isFalse()
    }

    @Test fun releaseRevalidatesCurrentLayoutAndPage() {
        var page = 0
        var free = true
        var result: WidgetPlacement? = null
        controller.target = { if (free) WidgetPlacement(page, 1, 1, 2, 2) else null }
        controller.onDrop = { _, target -> result = target }
        controller.start(drag, Offset.Zero)
        controller.move(Offset(50f, 50f))
        page = 3
        controller.finish()
        assertThat(result?.page).isEqualTo(3)
        controller.start(drag, Offset.Zero)
        controller.move(Offset(50f, 50f))
        free = false
        controller.finish()
        assertThat(result).isNull()
    }

    @Test fun endingADragInsideEditModeKeepsTheSwipeUpLocked() {
        // The edit frame owns the root swipe-up lock for its whole lifetime; a body tap/drag that
        // ends inside it must not open a window where a handle drag leaks into the app drawer.
        controller.keepsGestureLock = { true }
        controller.onStill = {}
        controller.start(drag, Offset.Zero)
        controller.finish()
        assertThat(controller.active).isNull()
        assertThat(home.localGestureActive).isTrue()
    }

    @Test fun aSecondGestureCannotTakeOverAnActiveDrag() {
        // Two fingers long-pressing two widgets: the later pickup must not replace the first drag.
        val other = drag.copy(item = EditingItem(2, null, 0, 2, 2, 1, 1))
        assertThat(controller.start(drag, Offset.Zero, Any())).isTrue()
        assertThat(controller.start(other, Offset.Zero, Any())).isFalse()
        assertThat(controller.active).isEqualTo(drag)
    }

    @Test fun onlyTheOwningGestureCanMoveDropOrCancel() {
        var drops = 0
        controller.onDrop = { _, _ -> drops++ }
        val owner = Any()
        val intruder = Any()
        controller.start(drag, Offset.Zero, owner)
        controller.move(Offset(50f, 50f), intruder)
        assertThat(controller.moving).isFalse()
        controller.finish(intruder)
        controller.cancel(intruder)
        assertThat(controller.active).isEqualTo(drag)
        assertThat(home.localGestureActive).isTrue()
        controller.move(Offset(50f, 50f), owner)
        controller.finish(owner)
        assertThat(drops).isEqualTo(1)
        assertThat(controller.active).isNull()
    }

    @Test fun failedDropAlwaysReleasesGestureOwnership() {
        controller.onDrop = { _, _ -> error("Write failed") }
        controller.start(drag, Offset.Zero)
        controller.move(Offset(50f, 50f))
        runCatching { controller.finish() }
        assertThat(controller.active).isNull()
        assertThat(home.localGestureActive).isFalse()
        assertThat(home.localDragging).isFalse()
    }
}
