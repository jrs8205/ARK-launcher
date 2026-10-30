package org.arkikeskus.launcher.ui

import android.os.Process
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HomeDragController.leftSource]: a cross-surface drag counts as "over the home screen" only once
 * the finger has left the surface it lifted from. Home, dock and drawer drags leave with their first
 * move; a drag inside the open folder's card moves without leaving (a reorder) until it crosses the
 * card's edge, so the home surfaces draw no drop cue and flip no page behind the card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeDragControllerTest {

    private val app = AppItem("p", "p.Main", Process.myUserHandle(), 0L, "App")
    private val c = HomeDragController()
    private val token = Any()

    @Test
    fun leftSourceIsFalseAfterStartAndTrueAfterBeginMove() {
        assertThat(c.start(app, DragSource.Drawer, Offset.Zero, token)).isTrue()
        assertThat(c.leftSource).isFalse()

        c.beginMove()

        assertThat(c.moving).isTrue()
        assertThat(c.leftSource).isTrue()
        assertThat(c.isIncomingFrom(DragSource.Drawer)).isTrue()
        assertThat(c.isIncomingFrom(DragSource.Dock)).isFalse()
    }

    @Test
    fun aFolderReorderMovesWithoutLeavingItsSource() {
        c.start(app, DragSource.Folder, Offset.Zero, token)

        c.beginMove(leftSource = false)

        assertThat(c.moving).isTrue()
        assertThat(c.leftSource).isFalse()
        assertThat(c.isIncomingFrom(DragSource.Folder)).isFalse()
        c.update(Offset(5f, 5f))
        assertThat(c.leftSource).isFalse()

        c.markLeftSource()

        assertThat(c.isIncomingFrom(DragSource.Folder)).isTrue()
        assertThat(c.isIncomingFrom(DragSource.Drawer, DragSource.Dock, DragSource.Folder)).isTrue()
    }

    @Test
    fun stopAndCancelResetLeftSource() {
        c.start(app, DragSource.Dock, Offset.Zero, token)
        c.beginMove()
        c.stop(token)
        assertThat(c.leftSource).isFalse()
        assertThat(c.isDragging).isFalse()

        c.start(app, DragSource.Dock, Offset.Zero, token)
        c.beginMove()
        c.cancel()
        assertThat(c.leftSource).isFalse()
        assertThat(c.isDragging).isFalse()
    }

    @Test
    fun isIncomingFromIsFalseWhileOnlyLifted() {
        c.start(app, DragSource.Dock, Offset.Zero, token)

        assertThat(c.isIncomingFrom(DragSource.Dock)).isFalse()
    }

    // --- Folders and the dock's drop targets ------------------------------------------------------

    @Test
    fun aLiftedFolderCountsAsADragUntilStoppedOrCancelled() {
        assertThat(c.startFolder(7L, DragSource.Dock, Offset.Zero, token)).isTrue()
        assertThat(c.draggedFolderId).isEqualTo(7L)
        assertThat(c.draggedApp).isNull()
        assertThat(c.isDragging).isTrue()
        assertThat(c.isIncomingFrom(DragSource.Dock)).isFalse()

        c.beginMove()
        assertThat(c.isIncomingFrom(DragSource.Dock)).isTrue()
        // A second lift is refused while the folder is held.
        assertThat(c.start(app, DragSource.Home, Offset.Zero, Any())).isFalse()

        c.stop(token)
        assertThat(c.draggedFolderId).isNull()
        assertThat(c.isDragging).isFalse()

        c.startFolder(7L, DragSource.Home, Offset.Zero, token)
        c.cancel()
        assertThat(c.draggedFolderId).isNull()
        assertThat(c.isDragging).isFalse()
    }

    @Test
    fun aDropOnTheDockLandsOnATileOrBetweenTiles() {
        c.dockBounds = Rect(0f, 100f, 300f, 160f)
        c.dockItemCount = 3
        c.dockSlots = listOf(Rect(0f, 100f, 100f, 160f), Rect(100f, 100f, 200f, 160f), Rect(200f, 100f, 300f, 160f))

        // The middle half of a tile: onto it (fold with its app, join its folder).
        assertThat(c.dockTargetAt(Offset(150f, 130f))).isEqualTo(DockTarget.Onto(1))
        assertThat(c.dockTargetAt(Offset(126f, 130f))).isEqualTo(DockTarget.Onto(1))
        // Its outer quarters: between the tiles, before or after it.
        assertThat(c.dockTargetAt(Offset(110f, 130f))).isEqualTo(DockTarget.Between(1))
        assertThat(c.dockTargetAt(Offset(190f, 130f))).isEqualTo(DockTarget.Between(2))
        assertThat(c.dockTargetAt(Offset(295f, 130f))).isEqualTo(DockTarget.Between(3))
        assertThat(c.dockTargetAt(Offset(5f, 130f))).isEqualTo(DockTarget.Between(0))
    }

    @Test
    fun withoutPublishedSlotsTheDockIndexFollowsTheItemCount() {
        c.dockBounds = Rect(0f, 100f, 300f, 160f)
        c.dockItemCount = 3
        assertThat(c.dockIndexAt(Offset(150f, 130f))).isEqualTo(1)
        assertThat(c.dockTargetAt(Offset(150f, 130f))).isEqualTo(DockTarget.Between(1))
    }
}
