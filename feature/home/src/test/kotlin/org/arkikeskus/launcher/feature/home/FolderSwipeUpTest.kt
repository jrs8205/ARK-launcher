package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
 * Issue #17 item 3 (opt-in, Nova-style): with "Swipe up on a folder opens its first app" on, a swipe up
 * that starts on a home folder icon opens the folder's first app instead of the drawer; off, the swipe
 * drives the drawer as from anywhere else on home. Swipe down and taps are untouched either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FolderSwipeUpTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val chat = AppItem("com.chat", "chat.Main", Process.myUserHandle(), 0L, "Chat")
    private val tools = PlacedFolder(id = 7L, name = "Tools", apps = listOf(mail, chat), page = 0, cellX = 1, cellY = 2)
    private val swipedFolders = mutableListOf<Long>()
    private val opened = mutableListOf<Long>()
    private var drawerDrags = 0
    private var shadeOpens = 0

    private fun show(folderSwipeOpensFirstApp: Boolean) {
        compose.setContent {
            Box(
                Modifier
                    .size(300.dp, 400.dp)
                    .pixelHomeSwipe(
                        swipeUpForDrawer = true,
                        swipeDownForNotifications = true,
                        dragController = drag,
                        onDrawerDrag = { drawerDrags++ },
                        onDrawerSettle = {},
                        onOpenNotifications = { shadeOpens++ },
                        folderSwipeOpensFirstApp = folderSwipeOpensFirstApp,
                        onFolderSwipeUp = { swipedFolders += it },
                    ),
            ) {
                Workspace(
                    pageCount = 1, columns = 4, rows = 6, entries = listOf(tools),
                    badges = emptyMap(), badgeShowCount = false, badgeScale = 1f, showLabels = true, showPageIndicator = true,
                    locked = false, homeSignals = emptyFlow(), homePage = 0, dragController = drag,
                    widgetDragController = remember { WidgetDragController(drag) },
                    onAddWidget = { _, _ -> }, onAppClick = {}, onAppMenu = { _, _, _ -> },
                    onMove = { _, _, _, _ -> true }, onMoveFolder = { _, _, _, _ -> true }, onDropOnDock = { _, _ -> },
                    onRemoveFromHome = {}, onOpenFolder = { opened += it.id }, onLaunchShortcut = {}, onRemoveShortcut = {},
                    onCreateFolder = { _, _ -> }, onAddToFolder = { _, _ -> }, onEmptyAreaMenu = { _, _ -> },
                    modifier = Modifier.size(300.dp, 400.dp),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun swipeUpOnTheFolder() {
        compose.onNodeWithText("Tools").performTouchInput {
            swipe(start = Offset(width / 2f, height - 2f), end = Offset(width / 2f, -height * 2f), durationMillis = 100)
        }
        compose.waitForIdle()
    }

    @Test fun withTheSettingASwipeUpOnAFolderOpensItsFirstAppNotTheDrawer() {
        show(folderSwipeOpensFirstApp = true)

        swipeUpOnTheFolder()

        assertThat(swipedFolders).containsExactly(7L)
        assertThat(drawerDrags).isEqualTo(0)
        assertThat(opened).isEmpty() // not a tap either
    }

    @Test fun withoutTheSettingASwipeUpOnAFolderDrivesTheDrawer() {
        show(folderSwipeOpensFirstApp = false)

        swipeUpOnTheFolder()

        assertThat(swipedFolders).isEmpty()
        assertThat(drawerDrags).isGreaterThan(0)
        assertThat(opened).isEmpty()
    }

    @Test fun aSwipeDownOnAFolderStillOpensTheShadeAndATapStillOpensTheFolder() {
        show(folderSwipeOpensFirstApp = true)

        compose.onNodeWithText("Tools").performTouchInput {
            swipe(start = Offset(width / 2f, 2f), end = Offset(width / 2f, height * 3f), durationMillis = 100)
        }
        compose.waitForIdle()
        assertThat(shadeOpens).isEqualTo(1)
        assertThat(swipedFolders).isEmpty()

        compose.onNodeWithText("Tools").performTouchInput { click() }
        compose.waitForIdle()
        assertThat(opened).containsExactly(7L)
        assertThat(swipedFolders).isEmpty()
    }

    /** The decision itself: the folder action wins over the drawer only with the setting on. */
    @Test fun theSwipeUpTargetRule() {
        assertThat(swipeUpTarget(folderUnderFinger = true, folderSwipeOpensFirstApp = true, swipeUpForDrawer = true)).isEqualTo(SwipeUpTarget.FOLDER_APP)
        assertThat(swipeUpTarget(folderUnderFinger = true, folderSwipeOpensFirstApp = true, swipeUpForDrawer = false)).isEqualTo(SwipeUpTarget.FOLDER_APP)
        assertThat(swipeUpTarget(folderUnderFinger = true, folderSwipeOpensFirstApp = false, swipeUpForDrawer = true)).isEqualTo(SwipeUpTarget.DRAWER)
        assertThat(swipeUpTarget(folderUnderFinger = false, folderSwipeOpensFirstApp = true, swipeUpForDrawer = true)).isEqualTo(SwipeUpTarget.DRAWER)
        assertThat(swipeUpTarget(folderUnderFinger = false, folderSwipeOpensFirstApp = true, swipeUpForDrawer = false)).isEqualTo(SwipeUpTarget.NONE)
    }
}
