package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A drawer cell's gesture lives as long as the app's key, so it has to act on the app as it is NOW
 * (a picked icon, a new name) and start a drag from where the finger really is, also when the cell
 * is half scrolled under the grid's top edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerCellItemTest {
    @get:Rule val compose = createComposeRule()

    private fun app(i: Int, label: String) =
        AppItem(packageName = "com.app$i", className = "Main", user = Process.myUserHandle(), userSerial = 0, label = label)

    private val mail = app(0, "Mail")
    private val apps = mutableStateOf(listOf(mail) + (1..11).map { app(it, "App $it") })
    private val longClicked = mutableListOf<AppItem>()
    private var longPress = 0L
    private lateinit var grid: LazyGridState
    private val dragController = HomeDragController()
    private val clicked = mutableListOf<AppItem>()
    private var locked = false

    private fun compose() {
        compose.setContent {
            grid = rememberLazyGridState()
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            val haptics = LocalHapticFeedback.current
            Box(Modifier.size(300.dp)) {
                LazyVerticalGrid(columns = GridCells.Fixed(2), state = grid, modifier = Modifier.fillMaxSize()) {
                    appCells(
                        apps = apps.value, badges = emptyMap(), badgeShowCount = true, badgeScale = 1f,
                        showLabels = true, iconSize = 56.dp, onAppClick = { clicked += it }, onAppLongClick = { a, _ -> longClicked += a },
                        dragController = dragController, onDragOutStart = {}, onDropOnHome = { _, _, _, _ -> },
                        onDropOnDock = { _, _ -> }, haptics = haptics, locked = locked,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun holdAndRelease(label: String) {
        compose.onNodeWithText(label).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onFirst().performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test fun theMenuGetsTheAppAsItIsNow() {
        compose()
        holdAndRelease("Mail")
        assertThat(longClicked.last().customIcon).isNull()

        val picked = CustomIcon.FromPack("com.pack", "mail")
        apps.value = listOf(mail.copy(customIcon = picked)) + apps.value.drop(1)
        compose.waitForIdle()
        holdAndRelease("Mail")

        assertThat(longClicked.last().customIcon).isEqualTo(picked)
    }

    @Test fun aLiftFromAHalfHiddenCellStartsUnderTheFinger() {
        compose()
        // The first row scrolled half under the top edge: the icon is cut, the label still shows.
        compose.runOnIdle { runBlocking { grid.scrollToItem(0, 50) } }
        compose.waitForIdle()
        val finger = compose.onNodeWithText("Mail").fetchSemanticsNode().boundsInRoot.center

        compose.onNodeWithText("Mail").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()

        assertThat(dragController.isDragging).isTrue()
        assertThat(dragController.rootPosition.y).isWithin(1f).of(finger.y)
        compose.onAllNodes(isRoot()).onFirst().performTouchInput { up() }
    }

    private fun root() = compose.onAllNodes(isRoot()).onFirst()

    /**
     * Review round 2. On a locked desktop a hold opens the app's menu and lifts nothing, and the finger is
     * still down. Its moves went on to the grid, which scrolled under the open menu: the menu then pointed at
     * a place its app had left. The finger that opened the menu moves nothing until it rises.
     */
    @Test fun onALockedDesktopTheGridStaysPutUnderTheFingerThatOpenedTheMenu() {
        locked = true
        compose()

        compose.onNodeWithText("Mail").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        assertThat(longClicked).containsExactly(mail)
        root().performTouchInput { repeat(6) { moveBy(Offset(0f, -30f)) } }
        compose.waitForIdle()

        assertThat(grid.firstVisibleItemIndex).isEqualTo(0)
        assertThat(grid.firstVisibleItemScrollOffset).isEqualTo(0)
        root().performTouchInput { up() }
        compose.waitForIdle()
        assertThat(clicked).isEmpty()
    }

    /** A pull that starts before the hold completes is the grid's scroll, locked or not. */
    @Test fun onALockedDesktopAPullBeforeTheHoldStillScrollsTheGrid() {
        locked = true
        compose()

        compose.onNodeWithText("Mail").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -30f)) }
            up()
        }
        compose.waitForIdle()

        assertThat(grid.firstVisibleItemIndex + grid.firstVisibleItemScrollOffset).isGreaterThan(0)
        assertThat(longClicked).isEmpty()
    }

    /**
     * Review round 2. The menu could be opened by the pointer gesture alone: nothing told a screen reader that
     * the app has one (the home folder's card did). The app's cell offers its tap and its hold as actions, on
     * a locked desktop too, where the hold opens the menu's short form.
     */
    @Test fun theCellOffersItsTapAndItsMenuToAScreenReader() {
        compose()

        compose.onNodeWithText("Mail").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertThat(longClicked).containsExactly(mail)
        assertThat(dragController.isDragging).isFalse()

        compose.onNodeWithText("Mail").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertThat(clicked).containsExactly(mail)
    }

    @Test fun onALockedDesktopTheCellStillOffersItsMenuToAScreenReader() {
        locked = true
        compose()

        compose.onNodeWithText("Mail").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()

        assertThat(longClicked).containsExactly(mail)
    }
}
