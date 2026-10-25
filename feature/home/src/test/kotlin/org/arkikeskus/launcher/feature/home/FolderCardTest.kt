package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The open folder as a card in the home window. A tap launches and closes; a still long-press lifts
 * the icon and, released still, opens the same menu as on the home screen; a locked desktop ignores
 * the long-press altogether; the scrim and Back close the card; the semantics actions keep working
 * for users who cannot make the pointer gesture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FolderCardTest {
    @get:Rule val compose = createComposeRule()

    private val user = Process.myUserHandle()
    private val mail = AppItem(RuntimeEnvironment.getApplication().packageName, "Mail", user, 0L, "Mail")
    private val maps = AppItem("com.maps", "Main", user, 0L, "Maps")
    private val notes = AppItem("com.notes", "Main", user, 0L, "Notes")
    private var folderState by mutableStateOf(
        PlacedFolder(id = 7, name = "Tools", apps = listOf(mail, maps, notes), page = 0, cellX = 0, cellY = 0),
    )
    private val drag = HomeDragController()

    private val launched = mutableListOf<AppItem>()
    private val renamed = mutableListOf<AppItem>()
    private val removed = mutableListOf<AppItem>()
    private val reorders = mutableListOf<Pair<AppItem, AppItem?>>()
    private val homeDrops = mutableListOf<Pair<AppItem, Triple<Int, Int, Int>>>()
    private val dockDrops = mutableListOf<AppItem>()
    private val removeDrops = mutableListOf<AppItem>()
    private var dragOutEnds = 0
    private var dismissed = 0
    private var behindClicks = 0
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private var longPress = 0L

    private fun show(locked: Boolean = false) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            LauncherTheme {
                Box(Modifier.fillMaxSize()) {
                    // A home icon's stand-in behind the card, in the corner the scrim covers.
                    Text("Behind", Modifier.clickable { behindClicks++ })
                    FolderCard(
                        folder = folderState,
                        badges = emptyMap(),
                        badgeShowCount = false,
                        badgeScale = 1f,
                        locked = locked,
                        dragController = drag,
                        onRename = {},
                        onAppClick = { launched += it },
                        onRenameApp = { renamed += it },
                        onRemoveFromFolder = { removed += it },
                        onPinShortcut = {},
                        onReorder = { app, before -> reorders += app to before },
                        onDropOnHome = { app, page, cellX, cellY -> homeDrops += app to Triple(page, cellX, cellY) },
                        onDropOnDock = { dockDrops += it },
                        onDropOnRemove = { removeDrops += it },
                        onDragOutEnd = { dragOutEnds++ },
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** Finger down on the icon, held past the long-press timeout (the lift). */
    private fun hold(label: String) {
        compose.onNodeWithText(label).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
    }

    private fun release(label: String) {
        compose.onNodeWithText(label).performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun assertNothingDropped() {
        assertThat(reorders).isEmpty()
        assertThat(homeDrops).isEmpty()
        assertThat(dockDrops).isEmpty()
        assertThat(removeDrops).isEmpty()
    }

    @Test fun aTapLaunchesTheAppAndClosesTheFolder() {
        show()

        compose.onNodeWithText("Maps").performClick()
        compose.waitForIdle()

        assertThat(launched).containsExactly(maps)
        assertThat(dismissed).isEqualTo(1)
    }

    /** An app left the open folder (the menu's "Remove from folder", an uninstall) and the next one
     *  slid into its slot: the DOWN hit-test must find the app that is there now, not the one that
     *  was — a stale rectangle made the slot dead to taps and holds until the folder was reopened. */
    @Test fun anAppThatSlidIntoARemovedAppsSlotAnswersTapsAndHolds() {
        show()
        val slot = rootCenter("Maps")

        compose.runOnIdle { folderState = folderState.copy(apps = listOf(mail, notes)) }
        compose.waitForIdle()
        assertThat(rootCenter("Notes")).isEqualTo(slot)

        cardRoot().performTouchInput { click(slot) }
        compose.waitForIdle()
        assertThat(launched).containsExactly(notes)

        cardRoot().performTouchInput { down(slot) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        assertThat(drag.draggedApp).isEqualTo(notes)
        cardRoot().performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertIsDisplayed()
    }

    @Test fun aStillLongPressOpensTheMenuAndLeavesTheApp() {
        show()

        hold("Mail")
        release("Mail")

        compose.onNodeWithText("App info").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Remove from folder").assertIsDisplayed()
        compose.onNodeWithText("Uninstall").assertIsDisplayed()
        assertThat(removed).isEmpty()
        assertThat(launched).isEmpty()
        assertThat(dismissed).isEqualTo(0)
        assertThat(drag.isDragging).isFalse()
        assertNothingDropped()
    }

    /** Issue #12: the menu opens as the icon lifts, while the finger is still down. */
    @Test fun aStillHoldOpensTheMenuBeforeTheFingerLifts() {
        show()

        hold("Mail")

        compose.onNodeWithText("App info").assertIsDisplayed()
        assertThat(launched).isEmpty()
        assertThat(drag.moving).isFalse()
    }

    /** Pulling the lifted icon on closes its open menu and the drag goes on as before. */
    @Test fun pullingTheIconOnAfterTheMenuOpenedClosesItAndReorders() {
        show()
        val mailCenter = rootCenter("Mail")
        val notesCenter = rootCenter("Notes")

        hold("Notes")
        compose.onNodeWithText("App info").assertIsDisplayed()
        moveTo((notesCenter + mailCenter) / 2f)
        moveTo(mailCenter)

        compose.onNodeWithText("App info").assertDoesNotExist()
        assertThat(drag.moving).isTrue()
        release("Notes")
        assertThat(reorders).containsExactly(notes to mail)
    }

    @Test fun removeFromFolderAndRenameAreMenuActions() {
        show()

        hold("Mail")
        release("Mail")
        compose.onNodeWithText("Remove from folder").performClick()
        compose.waitForIdle()
        assertThat(removed).containsExactly(mail)

        hold("Maps")
        release("Maps")
        compose.onNodeWithText("Rename").performClick()
        compose.waitForIdle()
        assertThat(renamed).containsExactly(maps)
    }

    @Test fun aLockedDesktopIgnoresTheLongPressButStillLaunchesOnTap() {
        show(locked = true)

        hold("Mail")
        assertThat(drag.isDragging).isFalse()
        release("Mail")
        compose.onNodeWithText("App info").assertDoesNotExist()

        compose.onNodeWithText("Maps").performClick()
        compose.waitForIdle()
        assertThat(launched).containsExactly(maps)
        assertNothingDropped()
    }

    @Test fun aScrimTapCloses() {
        show()

        compose.onNodeWithTag("folderScrim").performTouchInput { click(Offset(10f, 10f)) }
        compose.waitForIdle()

        assertThat(dismissed).isEqualTo(1)
        assertThat(launched).isEmpty()
    }

    @Test fun backCloses() {
        show()

        compose.runOnUiThread { backDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertThat(dismissed).isEqualTo(1)
    }

    @Test fun aSecondFingerIsRefusedWhileOneIconIsLifted() {
        show()

        hold("Mail")
        compose.onNodeWithText("Maps").performTouchInput { down(1, center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        assertThat(drag.draggedApp).isEqualTo(mail)

        compose.onNodeWithText("Maps").performTouchInput { up(1) }
        release("Mail")

        compose.onNodeWithText("App info").assertIsDisplayed()
        assertThat(launched).isEmpty()
        assertNothingDropped()
    }

    /** Compose's drag() hands a gesture over to another pressed finger when the tracked one lifts.
     *  The folder follows the lifting finger alone: when it rises the lift ends (a still hold, so the
     *  menu), and the finger still resting in the grid moves nothing and drops nothing. */
    @Test fun theLiftEndsWhenTheLiftingFingerRisesWhileASecondFingerRests() {
        show()
        homeBelowTheCard()

        hold("Mail")
        compose.onNodeWithText("Maps").performTouchInput { down(1, center) }
        compose.waitForIdle()
        compose.onNodeWithText("Mail").performTouchInput { up(0) }
        compose.waitForIdle()

        compose.onNodeWithText("App info").assertIsDisplayed()
        assertThat(drag.isDragging).isFalse()

        compose.onNodeWithText("Maps").performTouchInput { moveTo(1, center + Offset(0f, 400f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Maps").performTouchInput { up(1) }
        compose.waitForIdle()

        assertThat(drag.isDragging).isFalse()
        assertNothingDropped()
        assertThat(launched).isEmpty()
        assertThat(dragOutEnds).isEqualTo(0)
    }

    @Test fun theSemanticsClickLaunchesWithoutThePointerGesture() {
        show()

        compose.onNodeWithText("Maps").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertThat(launched).containsExactly(maps)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test fun theSemanticsLongClickOpensTheMenuWithoutThePointerGesture() {
        show()

        compose.onNodeWithText("Mail").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()

        compose.onNodeWithText("App info").assertIsDisplayed()
        assertThat(launched).isEmpty()
    }

    @Test fun aLockedDesktopExposesNoLongClickAction() {
        show(locked = true)

        compose.onNodeWithText("Mail").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        compose.onNodeWithText("Mail").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
    }

    private fun composeView(): ViewRootForTest {
        fun find(view: View): ViewRootForTest? = when (view) {
            is ViewRootForTest -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return checkNotNull(find((compose as AndroidComposeTestRule<*, *>).activity.window.decorView))
    }

    /** The home screen behind the open folder is out of reach for accessibility services too: the
     *  scrim covers it as a pane named after the folder (announced when it opens, dismissable), so a
     *  swipe through the nodes cannot land on a home icon, a widget or the dock behind the card and a
     *  double-tap there cannot fire. The folder's own apps stay reachable. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // the platform delegate finds its nodes with Region
    fun theHomeScreenBehindTheCardIsHiddenFromAccessibilityServices() {
        show()
        compose.runOnUiThread {
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(AccessibilityManager::class.java)).setEnabled(true)
            composeView().forceAccessibilityForTesting(true)
        }
        val behind = compose.onNodeWithText("Behind").fetchSemanticsNode().id
        val maps = compose.onNodeWithText("Maps").fetchSemanticsNode().id

        compose.runOnIdle {
            val provider = composeView().view.accessibilityNodeProvider
            assertThat(provider.createAccessibilityNodeInfo(behind)).isNull()
            assertThat(provider.createAccessibilityNodeInfo(maps)).isNotNull()
        }
        compose.onNodeWithTag("folderScrim").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Tools"))
        compose.onNodeWithTag("folderScrim").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
    }

    @Test fun theSemanticsDismissOnTheScrimCloses() {
        show()

        compose.onNodeWithTag("folderScrim").performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()

        assertThat(dismissed).isEqualTo(1)
    }

    // --- reorder inside the card ---

    /** The folder card's own window: while an icon is held its menu is open too, in a window of its own
     *  (issue #12), and the held gesture belongs to the window it started in. */
    private fun cardRoot() = compose.onAllNodes(isRoot()).onFirst()

    private fun rootCenter(label: String): Offset = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.center

    private fun rootLeft(label: String): Float = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.left

    /** Continues the held gesture: the finger moves to [root] (root coordinates). */
    private fun moveTo(root: Offset) {
        cardRoot().performTouchInput { moveTo(root) }
        compose.waitForIdle()
    }

    @Test fun reorderedFolderAppsMovesToFrontMiddleAndEnd() {
        val a = mail
        val b = maps
        val c = notes
        val d = AppItem("com.d", "Main", user, 0L, "D")
        val apps = listOf(a, b, c, d)

        assertThat(reorderedFolderApps(apps, d, 0)).containsExactly(d, a, b, c).inOrder()
        assertThat(reorderedFolderApps(apps, a, 3)).containsExactly(b, c, d, a).inOrder()
        assertThat(reorderedFolderApps(apps, d, 1)).containsExactly(a, d, b, c).inOrder()
        assertThat(reorderedFolderApps(apps, b, 1)).containsExactly(a, b, c, d).inOrder()
        assertThat(reorderedFolderApps(apps, a, 99)).containsExactly(b, c, d, a).inOrder()
    }

    @Test fun folderSlotIndexReadsTheRowFromTheVisibleBandsAndClampsToTheApps() {
        // The grid scrolled: rows 2 and 3 visible, row 2 partly out at the top and taller than row 3.
        val rows = listOf(RowBand(row = 2, top = -40), RowBand(row = 3, top = 90))
        assertThat(folderSlotIndex(Offset(10f, 10f), 400f, rows, 20)).isEqualTo(8)
        assertThat(folderSlotIndex(Offset(350f, 80f), 400f, rows, 20)).isEqualTo(11)
        assertThat(folderSlotIndex(Offset(10f, 95f), 400f, rows, 20)).isEqualTo(12)
        // Below the last visible row: that row. Above the first: the first. Never past the last app.
        assertThat(folderSlotIndex(Offset(350f, 400f), 400f, rows, 20)).isEqualTo(15)
        assertThat(folderSlotIndex(Offset(350f, 400f), 400f, rows, 14)).isEqualTo(13)
        assertThat(folderSlotIndex(Offset(-5f, -100f), 400f, rows, 20)).isEqualTo(8)
        assertThat(folderSlotIndex(Offset(10f, 10f), 400f, emptyList(), 20)).isEqualTo(0)
    }

    @Test fun aLongPressAndMoveInsideTheCardReordersAndReportsTheNeighbour() {
        show()
        val mailCenter = rootCenter("Mail")
        val notesCenter = rootCenter("Notes")

        hold("Notes")
        moveTo((notesCenter + mailCenter) / 2f)
        moveTo(mailCenter)
        assertThat(drag.moving).isTrue()
        assertThat(drag.leftSource).isFalse()
        assertThat(drag.source).isEqualTo(DragSource.Folder)
        release("Notes")

        assertThat(reorders).containsExactly(notes to mail)
        // The optimistic preview stays until the folder's apps change.
        assertThat(rootLeft("Notes")).isLessThan(rootLeft("Mail"))
        assertThat(rootLeft("Mail")).isLessThan(rootLeft("Maps"))
        assertThat(homeDrops).isEmpty()
        assertThat(dockDrops).isEmpty()
        assertThat(dragOutEnds).isEqualTo(0)
        assertThat(dismissed).isEqualTo(0)
    }

    @Test fun aMoveThatEndsInTheSameSlotWritesNothing() {
        show()
        val mailCenter = rootCenter("Mail")

        hold("Mail")
        moveTo(mailCenter + Offset(60f, 0f))
        moveTo(mailCenter)
        assertThat(drag.moving).isTrue()
        release("Mail")

        assertThat(reorders).isEmpty()
        compose.onNodeWithText("App info").assertDoesNotExist()
    }

    /** A pull upwards from the second row would scroll the grid a row (the first row out of sight);
     *  with an icon lifted the grid holds still and the pull is the reorder. */
    @Test fun theGridDoesNotScrollWhileAnItemIsLifted() {
        folderState = folderState.copy(apps = (1..24).map { AppItem("com.a$it", "Main", user, 0L, "a$it") })
        show()
        compose.onNodeWithText("a24").assertDoesNotExist()
        val a1 = rootCenter("a1")

        hold("a5")
        moveTo(a1)
        assertThat(drag.moving).isTrue()

        // The first row is where it was (the live reorder only moves a1 sideways within it).
        compose.onNodeWithText("a1").assertIsDisplayed()
        compose.onNodeWithText("a2").assertIsDisplayed()
        assertThat(rootCenter("a1").y).isEqualTo(a1.y)
        compose.onNodeWithText("a24").assertDoesNotExist()
        release("a5")
    }

    /** A long folder scrolled down, its first visible icon lifted and dragged to a lower row: the
     *  preview moves that icon's key down the list, and the grid — which anchors its scroll position to
     *  the first visible item's KEY — would follow it by a row under a finger that has not moved, and
     *  read the slot a row lower again. The grid keeps its position by index while the preview changes. */
    @Test fun aLiftedFirstVisibleIconDraggedDownDoesNotScrollTheGridUnderTheFinger() {
        val apps = (1..24).map { AppItem("com.a$it", "Main", user, 0L, "a$it") }
        folderState = folderState.copy(apps = apps)
        show()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(8)
        compose.waitForIdle()
        val a9 = rootCenter("a9")
        val a13 = rootCenter("a13")

        hold("a9")
        moveTo(a13)
        moveTo(a13 + Offset(0f, 1f))
        // a10 took the lifted icon's slot; the rows themselves did not move.
        assertThat(rootCenter("a10")).isEqualTo(a9)
        release("a9")

        assertThat(reorders).containsExactly(apps[8] to apps[13])
    }

    /** Rows are not all the same height when a name wraps to a second line, and the first visible
     *  icon's own height is no other row's: the slot under the finger comes from the visible rows'
     *  real bands. Here the first icon's name wraps (a tall first row) and the finger is near the top
     *  of a slot in the third row, which the first icon's height as the row height put in the second. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement: a long name wraps
    fun aReorderTargetsTheRowUnderTheFingerWhenRowsDifferInHeight() {
        val apps = (1..12).map { AppItem("com.a$it", "Main", user, 0L, "a$it") }.toMutableList()
        apps[0] = AppItem("com.a1", "Main", user, 0L, "An application name that wraps")
        folderState = folderState.copy(apps = apps)
        show()
        val a10 = compose.onNodeWithText("a10").fetchSemanticsNode().boundsInRoot
        val nearTheTopOfA10 = Offset(a10.center.x, a10.top + a10.height * 0.1f)

        hold("a9")
        moveTo(Offset(a10.center.x, a10.center.y))
        moveTo(nearTheTopOfA10)
        release("a9")

        assertThat(reorders).containsExactly(apps[8] to apps[10])
    }

    @Test fun theDatabaseOrderReplacesThePreviewWhenItArrives() {
        show()
        val mailCenter = rootCenter("Mail")
        hold("Notes")
        moveTo(mailCenter)
        release("Notes")
        assertThat(reorders).containsExactly(notes to mail)

        compose.runOnIdle { folderState = folderState.copy(apps = listOf(notes, mail, maps)) }
        compose.waitForIdle()
        assertThat(rootLeft("Notes")).isLessThan(rootLeft("Mail"))
        assertThat(rootLeft("Mail")).isLessThan(rootLeft("Maps"))

        compose.runOnIdle { folderState = folderState.copy(apps = listOf(maps, notes, mail)) }
        compose.waitForIdle()
        assertThat(rootLeft("Maps")).isLessThan(rootLeft("Notes"))
        assertThat(rootLeft("Notes")).isLessThan(rootLeft("Mail"))
    }

    // --- drag out of the card ---

    private fun cardBounds(): Rect = compose.onNodeWithTag("folderCard").fetchSemanticsNode().boundsInRoot
    private fun rootBounds(): Rect = cardRoot().fetchSemanticsNode().boundsInRoot

    /** A point on the home screen below the card. */
    private fun below(): Offset {
        val card = cardBounds()
        val root = rootBounds()
        return Offset(root.center.x, (card.bottom + root.bottom) / 2f)
    }

    /** Lifts [label] and pulls it out through the card's bottom edge to [to]; the card stays composed. */
    private fun dragOut(label: String, to: Offset = below()) {
        val card = cardBounds()
        hold(label)
        moveTo(Offset(rootCenter(label).x, card.bottom - 1f))
        moveTo(to)
        assertThat(drag.moving).isTrue()
        assertThat(drag.leftSource).isTrue()
        assertThat(drag.source).isEqualTo(DragSource.Folder)
        compose.onNodeWithTag("folderCard").assertExists()
    }

    private fun homeBelowTheCard() {
        val card = cardBounds()
        val root = rootBounds()
        compose.runOnUiThread {
            drag.gridBounds = Rect(0f, card.bottom + 1f, root.right, root.bottom)
            drag.columns = 4
            drag.rows = 5
            drag.currentPage = 2
        }
    }

    @Test fun draggingPastTheCardEdgeLeavesTheSourceAndDropsOnTheHomeCell() {
        show()
        homeBelowTheCard()
        val target = below()

        dragOut("Mail", target)
        release("Mail")

        assertThat(homeDrops).containsExactly(mail to drag.cellAt(target))
        assertThat(dragOutEnds).isEqualTo(1)
        assertThat(reorders).isEmpty()
        assertThat(dockDrops).isEmpty()
        assertThat(drag.isDragging).isFalse()
    }

    @Test fun aDropOnTheDockWithRoomGoesToTheDock() {
        show()
        val card = cardBounds()
        val root = rootBounds()
        compose.runOnUiThread {
            drag.dockBounds = Rect(0f, root.bottom - 100f, root.right, root.bottom)
            drag.dockHasSpace = true
            drag.gridBounds = Rect(0f, card.bottom + 1f, root.right, root.bottom - 100f)
        }

        dragOut("Maps", Offset(root.center.x, root.bottom - 50f))
        release("Maps")

        assertThat(dockDrops).containsExactly(maps)
        assertThat(homeDrops).isEmpty()
        assertThat(dragOutEnds).isEqualTo(1)
    }

    @Test fun aDropOnAFullDockCallsNothingButStillEndsTheDragOut() {
        show()
        val card = cardBounds()
        val root = rootBounds()
        compose.runOnUiThread {
            drag.dockBounds = Rect(0f, root.bottom - 100f, root.right, root.bottom)
            drag.dockHasSpace = false
            drag.gridBounds = Rect(0f, card.bottom + 1f, root.right, root.bottom - 100f)
        }

        dragOut("Maps", Offset(root.center.x, root.bottom - 50f))
        release("Maps")

        assertNothingDropped()
        assertThat(dragOutEnds).isEqualTo(1)
        assertThat(launched).isEmpty()
    }

    @Test fun aDropOnNothingCallsNothingButStillEndsTheDragOut() {
        show()
        compose.runOnUiThread {
            drag.gridBounds = Rect.Zero
            drag.dockBounds = Rect.Zero
            drag.removeBounds = Rect.Zero
        }

        dragOut("Mail")
        release("Mail")

        assertNothingDropped()
        assertThat(dragOutEnds).isEqualTo(1)
        assertThat(drag.isDragging).isFalse()
    }

    @Test fun aDropOnTheRemovePillRemovesEntirely() {
        show()
        homeBelowTheCard()
        val card = cardBounds()
        val root = rootBounds()
        compose.runOnUiThread { drag.removeBounds = Rect(root.center.x - 80f, 0f, root.center.x + 80f, 60f) }

        hold("Mail")
        moveTo(Offset(rootCenter("Mail").x, card.top + 1f))
        moveTo(Offset(root.center.x, 30f))
        assertThat(drag.leftSource).isTrue()
        release("Mail")

        assertThat(removeDrops).containsExactly(mail)
        assertThat(homeDrops).isEmpty()
        assertThat(dragOutEnds).isEqualTo(1)
    }

    @Test fun aMoveInsideTheCardNeverSetsLeftSource() {
        show()
        homeBelowTheCard()
        val mailCenter = rootCenter("Mail")

        hold("Notes")
        moveTo(mailCenter)
        assertThat(drag.moving).isTrue()
        assertThat(drag.leftSource).isFalse()
        release("Notes")

        assertThat(dragOutEnds).isEqualTo(0)
        assertThat(dismissed).isEqualTo(0)
        assertThat(homeDrops).isEmpty()
    }

    @Test fun aCancelledLiftDropsNothingOnRelease() {
        show()
        homeBelowTheCard()

        dragOut("Mail")
        compose.runOnUiThread { drag.cancel() }
        compose.waitForIdle()
        release("Mail")

        assertNothingDropped()
        compose.onNodeWithText("App info").assertDoesNotExist()
        assertThat(launched).isEmpty()
        assertThat(dragOutEnds).isEqualTo(1)
        assertThat(drag.isDragging).isFalse()
    }
}
