package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.DockTarget
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A folder in a dock slot: its tile shows the folder's name under the preview, a tap opens it, a
 * hold and a pull lift it into the shared controller as a folder drag out of the dock, and the dock
 * publishes its slots so a drop onto a tile can be told from a drop between tiles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DockFolderTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val chat = AppItem("com.chat", "chat.Main", Process.myUserHandle(), 0L, "Chat")
    private val tools = PlacedFolder(id = 7L, name = "Tools", apps = listOf(mail, chat), page = DOCK_PAGE, cellX = 1, cellY = 0)
    private val entries = mutableStateOf<List<DockEntry>>(listOf(DockApp(1L, maps), DockFolder(tools)))
    private val opened = mutableListOf<PlacedFolder>()
    private val reorders = mutableListOf<List<Long>>()
    private val folderMoves = mutableListOf<Triple<Int, Int, Int>>()
    private val tileDrops = mutableListOf<Pair<String, Int>>()
    private var longPress = 0L

    private fun show(showLabels: Boolean = true, showFolderNames: Boolean = true) {
        compose.setContent {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Dock(
                    entries = entries.value,
                    badges = emptyMap(),
                    badgeShowCount = false,
                    badgeScale = 1f,
                    showLabels = showLabels,
                    showFolderNames = showFolderNames,
                    backgroundAlpha = 1f,
                    locked = false,
                    dragController = drag,
                    onAppClick = {},
                    onReorder = { reorders += it.map { e -> e.rowId } },
                    onMoveToHome = { _, _, _, _ -> },
                    onOpenFolder = { opened += it },
                    onMoveFolderToHome = { _, page, x, y -> folderMoves += Triple(page, x, y) },
                    onDropOnTile = { app, target -> tileDrops += app.label to target.index },
                    modifier = Modifier.size(300.dp, 100.dp),
                    onAppMenu = { _, _ -> },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun aDockFolderShowsItsNameAndOpensOnTap() {
        show()
        compose.onNodeWithText("Tools").assertExists()

        compose.onNodeWithText("Tools").performClick()
        compose.waitForIdle()

        assertThat(opened.map { it.id }).containsExactly(7L)
        assertThat(drag.isDragging).isFalse()
    }

    @Test fun theFolderNameFollowsTheSwitchLikeOnHome() {
        show(showFolderNames = false)
        compose.onNodeWithText("Tools").assertDoesNotExist()
        compose.onNodeWithText("Maps").assertExists()
    }

    @Test fun aHeldDockFolderPulledAwayIsAFolderDragOutOfTheDock() {
        show()
        compose.onNodeWithText("Tools").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        assertThat(drag.draggedFolderId).isEqualTo(7L)
        assertThat(drag.source).isEqualTo(DragSource.Dock)
        assertThat(drag.moving).isFalse()

        compose.onNodeWithText("Tools").performTouchInput { repeat(8) { moveBy(Offset(0f, -40f)) } }
        compose.waitForIdle()

        assertThat(drag.moving).isTrue()
        assertThat(drag.isIncomingFrom(DragSource.Dock)).isTrue()
        assertThat(drag.draggedApp).isNull()

        compose.onNodeWithText("Tools").performTouchInput { up() }
        compose.waitForIdle()
        assertThat(drag.isDragging).isFalse()
    }

    @Test fun theDockPublishesItsSlotsForDropsOntoATile() {
        show()
        assertThat(drag.dockSlots).hasSize(2)
        val folderSlot = drag.dockSlots[1]
        assertThat(drag.dockTargetAt(folderSlot.center)).isEqualTo(DockTarget.Onto(1))
        assertThat(drag.dockTargetAt(Offset(folderSlot.left + 2f, folderSlot.center.y))).isEqualTo(DockTarget.Between(1))
    }

    @Test fun aDockAppDroppedOntoTheFolderTileJoinsItInsteadOfReordering() {
        show()
        val folderSlot = drag.dockSlots[1]
        compose.onNodeWithText("Maps").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        assertThat(drag.draggedApp).isEqualTo(maps)

        // Pull Maps onto the middle of the Tools tile and let go there.
        compose.onNodeWithText("Maps").performTouchInput {
            val mapsSlot = drag.dockSlots[0]
            val dx = folderSlot.center.x - mapsSlot.center.x
            repeat(10) { moveBy(Offset(dx / 10f, 0f)) }
        }
        compose.waitForIdle()
        assertThat(drag.dockTargetAt(drag.rootPosition)).isEqualTo(DockTarget.Onto(1))
        // Resting there arms the fold (a release while still on the move would reorder past it).
        compose.mainClock.advanceTimeBy(DOCK_FOLD_HOVER_MS + 50)
        compose.waitForIdle()
        compose.onNodeWithText("Maps").performTouchInput { up() }
        compose.waitForIdle()

        assertThat(tileDrops).containsExactly("Maps" to 1)
        assertThat(reorders).isEmpty()
    }

    @Test fun aDockAppReleasedOnAnotherTileWhileStillMovingReordersInstead() {
        show()
        val folderSlot = drag.dockSlots[1]
        compose.onNodeWithText("Maps").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        compose.onNodeWithText("Maps").performTouchInput {
            val dx = folderSlot.center.x - drag.dockSlots[0].center.x
            repeat(10) { moveBy(Offset(dx / 10f, 0f)) }
            up()
        }
        compose.waitForIdle()

        assertThat(tileDrops).isEmpty()
        assertThat(reorders).containsExactly(listOf(7L, 1L))
    }

    @Test fun aDockFolderOpensFromTheAccessibilityClickAction() {
        show()

        compose.onNodeWithText("Tools").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertThat(opened.map { it.id }).containsExactly(7L)
    }

    @Test fun aVerticalFlickFromADockFolderOpensNothingLikeFromADockApp() {
        // The dock owns every touch that starts on it (HomeScreen's root swipe detector bails over the
        // dock so a hold's drift never half-opens the drawer): a flick that leaves the tile is dropped,
        // it neither opens the folder nor lifts it.
        show()
        compose.onNodeWithText("Tools").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -60f)) }
            up()
        }
        compose.waitForIdle()

        assertThat(opened).isEmpty()
        assertThat(drag.isDragging).isFalse()
    }
}
