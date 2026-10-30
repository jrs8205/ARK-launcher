package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.AppLaunch
import org.arkikeskus.launcher.ui.AppLaunchSources
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.LocalAppLaunchSources
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * The home grid and the dock launch on a tap anywhere in an icon's cell, which is larger than the
 * icon: a tap at the cell's edge zooms from the icon too, not only one on the icon itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class LaunchZoomCellTest {
    @get:Rule val compose = createComposeRule()

    private val sources = AppLaunchSources()
    private val drag = HomeDragController()
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")
    private val launches = mutableListOf<Pair<AppItem, AppLaunch?>>()

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLaunchSources provides sources) {
                Column {
                    Workspace(
                        pageCount = 1,
                        columns = 3,
                        rows = 3,
                        entries = listOf(PlacedApp(mail, page = 0, cellX = 1, cellY = 1)),
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
                        onAppClick = { launches += it to sources.take(it) },
                        onAppMenu = { _, _, _ -> },
                        onMove = { _, _, _, _ -> true },
                        onMoveFolder = { _, _, _, _ -> true },
                        onDropOnDock = { _, _ -> },
                        onRemoveFromHome = {},
                        onOpenFolder = {},
                        onLaunchShortcut = {},
                        onRemoveShortcut = {},
                        onCreateFolder = { _, _ -> },
                        onAddToFolder = { _, _ -> },
                        onEmptyAreaMenu = { _, _ -> },
                        modifier = Modifier.size(300.dp, 300.dp),
                    )
                    Dock(
                        entries = listOf(DockApp(1L, maps)),
                        badges = emptyMap(),
                        badgeShowCount = false,
                        badgeScale = 1f,
                        showLabels = true,
                        backgroundAlpha = 1f,
                        locked = false,
                        dragController = drag,
                        onAppClick = { launches += it to sources.take(it) },
                        onReorder = {},
                        onMoveToHome = { _, _, _, _ -> },
                        modifier = Modifier.size(300.dp, 100.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun icon(app: AppItem): Rect = compose.onNodeWithContentDescription(app.label).fetchSemanticsNode().boundsInRoot

    private fun assertZoomsFrom(app: AppItem, icon: Rect) {
        val (launched, launch) = launches.single()
        assertThat(launched).isEqualTo(app)
        assertThat(launch).isNotNull()
        assertThat(launch!!.sourceBounds.left).isEqualTo(icon.left.roundToInt())
        assertThat(launch.sourceBounds.top).isEqualTo(icon.top.roundToInt())
        assertThat(launch.sourceBounds.width()).isEqualTo(icon.width.roundToInt())
    }

    @Test fun aTapAtAHomeCellsEdgeZoomsFromItsIcon() {
        show()
        val icon = icon(mail)

        // 100 dp cells around a smaller icon (the test screen is mdpi): left of the icon, still in its cell.
        compose.onRoot().performTouchInput { click(Offset(icon.left - 12f, icon.center.y)) }

        assertZoomsFrom(mail, icon)
    }

    @Test fun aTapAtADockCellsEdgeZoomsFromItsIcon() {
        show()
        val icon = icon(maps)

        compose.onRoot().performTouchInput { click(Offset(icon.left - 40f, icon.center.y)) }

        assertZoomsFrom(maps, icon)
    }
}
