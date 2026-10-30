package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An icon's gesture lives as long as the icon's key: it must act on the item as it is NOW, not as it
 * was at the first touch. With the first touch's item, Edit opened a second time showed the old icon,
 * and saving a new name wiped the picture picked a moment earlier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class IconMenuCurrentItemTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")
    private val picked = CustomIcon.FromPack("com.pack", "mail_icon")
    private val homeEntries = mutableStateOf<List<HomeEntry>>(listOf(PlacedApp(mail, page = 0, cellX = 0, cellY = 0)))
    private val dockApps = mutableStateOf(listOf<DockEntry>(DockApp(1L, maps)))
    private val homeMenus = mutableListOf<AppItem>()
    private val dockMenus = mutableListOf<AppItem>()
    private var longPress = 0L

    private fun show() {
        compose.setContent {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            Column {
                Workspace(
                    pageCount = 1,
                    columns = 4,
                    rows = 6,
                    entries = homeEntries.value,
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
                    onAppMenu = { app, _, _ -> homeMenus += app },
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
                    modifier = Modifier.size(300.dp, 320.dp),
                )
                Dock(
                    entries = dockApps.value,
                    badges = emptyMap(),
                    badgeShowCount = false,
                    badgeScale = 1f,
                    showLabels = true,
                    backgroundAlpha = 1f,
                    locked = false,
                    dragController = drag,
                    onAppClick = {},
                    onReorder = {},
                    onMoveToHome = { _, _, _, _ -> },
                    modifier = Modifier.size(300.dp, 100.dp),
                    onAppMenu = { app, _ -> dockMenus += app },
                )
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

    @Test fun aHomeIconsMenuGetsTheItemAsItIsNow() {
        show()
        holdAndRelease("Mail")
        assertThat(homeMenus.last().customIcon).isNull()

        // The user picked an icon for Mail: the same cell, the same key, a new item.
        homeEntries.value = listOf(PlacedApp(mail.copy(customIcon = picked), page = 0, cellX = 0, cellY = 0))
        compose.waitForIdle()
        holdAndRelease("Mail")

        assertThat(homeMenus.last().customIcon).isEqualTo(picked)
    }

    @Test fun aDockIconsMenuGetsTheItemAsItIsNow() {
        show()
        holdAndRelease("Maps")
        assertThat(dockMenus.last().customIcon).isNull()

        dockApps.value = listOf(DockApp(1L, maps.copy(customIcon = picked)))
        compose.waitForIdle()
        holdAndRelease("Maps")

        assertThat(dockMenus.last().customIcon).isEqualTo(picked)
    }
}
