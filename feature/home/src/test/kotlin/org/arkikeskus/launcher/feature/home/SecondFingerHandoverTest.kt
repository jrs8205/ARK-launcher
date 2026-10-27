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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A lifted icon belongs to the finger that lifted it. Compose's drag() hands the gesture over to
 * another finger pressed on the same node when the tracked one rises: a second finger resting on
 * the vacated cell (still hit-testable while the icon moves) then kept the app in the air, and the
 * drop happened wherever THAT finger lifted. The folder card already ends the lift with the lifting
 * finger; the home icons and the dock do the same now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SecondFingerHandoverTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")
    private val phone = AppItem("com.phone", "phone.Main", Process.myUserHandle(), 0L, "Phone")
    private val moves = mutableListOf<Triple<Int, Int, Int>>()
    private val reorders = mutableListOf<List<String>>()
    private var longPress = 0L
    private var cellWidthPx = 0f

    private fun show() {
        compose.setContent {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            cellWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 300.dp.toPx() } / 4f
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
                    locked = false,
                    homeSignals = emptyFlow(),
                    homePage = 0,
                    dragController = drag,
                    widgetDragController = remember { WidgetDragController(drag) },
                    onAddWidget = { _, _ -> },
                    onAppClick = {},
                    onAppMenu = { _, _, _ -> },
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
                    apps = listOf(maps, phone),
                    badges = emptyMap(),
                    badgeShowCount = false,
                    badgeScale = 1f,
                    showLabels = true,
                    backgroundAlpha = 1f,
                    locked = false,
                    dragController = drag,
                    onAppClick = {},
                    onReorder = { reorders += it.map { app -> app.label } },
                    onMoveToHome = { _, _, _, _ -> },
                    modifier = Modifier.size(300.dp, 100.dp),
                    onAppMenu = { _, _ -> },
                )
            }
        }
        compose.waitForIdle()
    }

    /** Finger 0 lifts [label] and pulls it [dx] px to the right in small steps. */
    private fun liftAndPull(label: String, dx: Float) {
        compose.onNodeWithText(label).performTouchInput { down(0, center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        compose.onNodeWithText(label).performTouchInput {
            repeat(10) { moveTo(0, center + Offset(dx * (it + 1) / 10f, 0f)) }
        }
        compose.waitForIdle()
        assertThat(drag.isDragging).isTrue()
    }

    @Test fun aHomeIconDropsWhereTheLiftingFingerRises() {
        show()
        liftAndPull("Mail", dx = cellWidthPx * 2)

        // A second finger rests on the vacated cell; the first one lets go two cells to the right.
        compose.onNodeWithText("Mail").performTouchInput { down(1, center) }
        compose.waitForIdle()
        compose.onNodeWithText("Mail").performTouchInput { up(0) }
        compose.waitForIdle()

        assertThat(moves).containsExactly(Triple(0, 2, 0))
        assertThat(drag.isDragging).isFalse()

        // The resting finger moves on and rises: nothing more happens.
        compose.onNodeWithText("Mail").performTouchInput { moveTo(1, center + Offset(0f, 200f)); up(1) }
        compose.waitForIdle()
        assertThat(moves).containsExactly(Triple(0, 2, 0))
    }

    @Test fun aDockIconReordersWhereTheLiftingFingerRises() {
        show()
        liftAndPull("Maps", dx = 150f)

        compose.onNodeWithText("Maps").performTouchInput { down(1, center) }
        compose.waitForIdle()
        compose.onNodeWithText("Maps").performTouchInput { up(0) }
        compose.waitForIdle()

        assertThat(reorders).containsExactly(listOf("Phone", "Maps"))
        assertThat(drag.isDragging).isFalse()

        compose.onNodeWithText("Maps").performTouchInput { moveTo(1, center + Offset(-100f, 0f)); up(1) }
        compose.waitForIdle()
        assertThat(reorders).containsExactly(listOf("Phone", "Maps"))
    }
}
