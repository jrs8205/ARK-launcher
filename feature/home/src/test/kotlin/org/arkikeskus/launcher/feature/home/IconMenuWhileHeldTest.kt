package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.DismissWhenDragMoves
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #12: an icon's menu opened only when the finger LIFTED after a long-press. Like Pixel Launcher,
 * it now opens the moment the icon lifts under the still-held finger; pulling the icon on from there
 * closes the menu and drags (see [DismissWhenDragMoves]), and releasing still leaves the menu open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class IconMenuWhileHeldTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")
    private val homeMenus = mutableListOf<AppItem>()
    private val dockMenus = mutableListOf<AppItem>()
    private val moves = mutableListOf<Triple<Int, Int, Int>>()
    private var launches = 0
    private var dismissals = 0
    private var longPress = 0L

    private fun show(locked: Boolean = false) {
        compose.setContent {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            DismissWhenDragMoves(drag) { dismissals++ }
            Column {
                Workspace(
                    pageCount = 1,
                    columns = 4,
                    rows = 6,
                    entries = listOf(PlacedApp(mail, page = 0, cellX = 0, cellY = 0)),
                    badges = emptyMap(),
                    badgeShowCount = false,
                    badgeScale = 1f,
                    showLabels = true,
                    showPageIndicator = true,
                    locked = locked,
                    homeSignals = emptyFlow(),
                    homePage = 0,
                    dragController = drag,
                    widgetDragController = remember { WidgetDragController(drag) },
                    onAddWidget = { _, _ -> },
                    onAppClick = { launches++ },
                    onAppMenu = { app, _, _ -> homeMenus += app },
                    onMove = { _, page, x, y -> moves += Triple(page, x, y); true },
                    onMoveFolder = { _, _, _, _ -> true },
                    onMoveToDock = { _, _ -> },
                    onRemoveFromHome = {},
                    onOpenFolder = {},
                    onLaunchShortcut = {},
                    onRemoveShortcut = {},
                    onCreateFolder = { _, _ -> },
                    onAddToFolder = { _, _ -> },
                    onEmptyAreaMenu = { _, _ -> },
                    modifier = Modifier.size(300.dp, 320.dp),
                )
                Dock(
                    apps = listOf(maps),
                    badges = emptyMap(),
                    badgeShowCount = false,
                    badgeScale = 1f,
                    showLabels = true,
                    backgroundAlpha = 1f,
                    locked = locked,
                    dragController = drag,
                    onAppClick = { launches++ },
                    onReorder = {},
                    onMoveToHome = { _, _, _, _ -> },
                    modifier = Modifier.size(300.dp, 100.dp),
                    onAppMenu = { app, _ -> dockMenus += app },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun hold(label: String) {
        compose.onNodeWithText(label).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
    }

    /** The launcher's own window: while an icon is held its menu is open too, in a window of its own,
     *  and the held gesture belongs to the window it started in. */
    private fun launcherRoot() = compose.onAllNodes(isRoot()).onFirst()

    private fun moveTo(root: Offset) {
        launcherRoot().performTouchInput { moveTo(root) }
        compose.waitForIdle()
    }

    private fun release() {
        launcherRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test fun aHomeIconsMenuOpensWhileTheFingerIsStillDown() {
        show()

        hold("Mail")

        assertThat(homeMenus).containsExactly(mail)
        release()
        assertThat(homeMenus).containsExactly(mail) // releasing does not open it again
        assertThat(launches).isEqualTo(0)
        assertThat(moves).isEmpty()
    }

    @Test fun aDockIconsMenuOpensWhileTheFingerIsStillDown() {
        show()

        hold("Maps")

        assertThat(dockMenus).containsExactly(maps)
        release()
        assertThat(dockMenus).containsExactly(maps)
        assertThat(launches).isEqualTo(0)
    }

    @Test fun pullingTheIconOnClosesTheMenuAndMovesIt() {
        show()
        val start = compose.onNodeWithText("Mail").fetchSemanticsNode().boundsInRoot.center

        hold("Mail")
        assertThat(homeMenus).containsExactly(mail)
        moveTo(start + Offset(40f, 0f))
        moveTo(start + Offset(160f, 0f))

        assertThat(drag.moving).isTrue()
        assertThat(dismissals).isEqualTo(1)
        release()
        assertThat(moves).isNotEmpty()
        assertThat(homeMenus).containsExactly(mail)
    }

    @Test fun aLockedDesktopOpensNoMenu() {
        show(locked = true)

        hold("Mail")
        release()

        assertThat(homeMenus).isEmpty()
    }
}
