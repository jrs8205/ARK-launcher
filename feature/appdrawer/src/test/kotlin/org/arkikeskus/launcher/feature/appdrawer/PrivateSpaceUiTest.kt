package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.component.PRIVATE_APP_MARK_TAG
import org.arkikeskus.launcher.ui.expressive.ExpressiveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Private tab's page. Locked: a lock mark, a line that says so and Unlock, which shows it is working
 * while Android's answer is on its way. Unlocked: the private apps' grid with a floating Lock button and,
 * when it has somewhere to lead, a settings button; and the Install tile at the end of the grid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PrivateSpaceUiTest {
    @get:Rule val compose = createComposeRule()

    private val asked = mutableListOf<Boolean>()
    private var settingsOpened = 0
    private var gridPadding: Dp? = null

    private fun page(space: PrivateSpaceUi) {
        compose.setContent {
            ExpressiveTheme {
                Box(Modifier.size(320.dp, 500.dp)) {
                    PrivatePage(
                        space = space,
                        onSetLocked = { asked += it },
                        onOpenSettings = { settingsOpened++ },
                        grid = { bottomPadding ->
                            gridPadding = bottomPadding
                            Box(Modifier.fillMaxSize().testTag("grid"))
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aLockedSpaceSaysSoAndOffersUnlock() {
        page(PrivateSpaceUi(locked = true))

        compose.onNodeWithText("Private space is locked").assertIsDisplayed()
        compose.onNodeWithTag("grid").assertDoesNotExist()
        compose.onNodeWithText("Lock").assertDoesNotExist()
        compose.onNodeWithTag(PRIVATE_UNLOCK_WORKING_TAG, useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithText("Unlock").assertIsEnabled().performClick()
        assertThat(asked).containsExactly(false)
    }

    @Test fun whileAndroidsAnswerIsOnItsWayUnlockShowsItIsWorkingAndTakesNoTap() {
        page(PrivateSpaceUi(locked = true, transitioning = true))

        compose.onNodeWithTag(PRIVATE_UNLOCK_WORKING_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Unlock").assertIsNotEnabled().performClick()
        assertThat(asked).isEmpty()
    }

    @Test fun theLockedPageHandsAPullDownToTheDrawer() {
        // The drawer's pull-to-close is a nested-scroll parent: only a scrollable page hands it a downward drag.
        var pulled = 0f
        val connection = object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) pulled += available.y
                return Offset.Zero
            }
        }
        compose.setContent {
            ExpressiveTheme {
                Box(Modifier.size(300.dp).nestedScroll(connection).testTag("host")) {
                    PrivatePage(PrivateSpaceUi(locked = true), onSetLocked = {}, onOpenSettings = {}, grid = {})
                }
            }
        }

        compose.onNodeWithTag("host").performTouchInput {
            swipe(start = Offset(150f, 40f), end = Offset(150f, 260f), durationMillis = 120)
        }
        compose.waitForIdle()

        assertThat(pulled).isGreaterThan(0f)
    }

    @Test fun anUnlockedSpaceShowsItsGridWithRoomUnderTheLastRowForTheButtons() {
        page(PrivateSpaceUi(locked = false))

        compose.onNodeWithTag("grid").assertExists()
        compose.onNodeWithText("Private space is locked").assertDoesNotExist()
        val button = compose.onNodeWithText("Lock").assertIsDisplayed().getBoundsInRoot().height
        // The grid is handed the buttons' measured height and their margin: its last row is never left under them.
        assertThat(gridPadding!!.value).isAtLeast(button.value + 16f)

        compose.onNodeWithText("Lock").performClick()
        assertThat(asked).containsExactly(true)
    }

    @Test fun lockTakesNoTapWhileARequestIsOnItsWay() {
        page(PrivateSpaceUi(locked = false, transitioning = true))

        compose.onNodeWithText("Lock").assertIsNotEnabled().performClick()
        assertThat(asked).isEmpty()
    }

    @Test fun theSettingsButtonIsThereOnlyWhenItLeadsSomewhere() {
        page(PrivateSpaceUi(locked = false, canOpenSettings = true))
        compose.onNodeWithContentDescription("Private space settings").assertIsDisplayed().performClick()
        assertThat(settingsOpened).isEqualTo(1)
    }

    @Test fun withoutATargetThereIsNoSettingsButton() {
        page(PrivateSpaceUi(locked = false, canOpenSettings = false))
        compose.onNodeWithContentDescription("Private space settings").assertDoesNotExist()
        compose.onNodeWithText("Lock").assertIsDisplayed()
    }

    @Test fun theInstallTileSitsInTheGridAndOpensTheStore() {
        var installs = 0
        compose.setContent {
            ExpressiveTheme {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.size(320.dp, 300.dp)) {
                    privateInstallTile(iconSize = 56.dp, showLabel = true, onInstall = { installs++ })
                }
            }
        }

        compose.onNodeWithText("Install").assertIsDisplayed().performClick()

        assertThat(installs).isEqualTo(1)
    }

    @Test fun aPrivateSearchResultCarriesTheLockMarkAndTheOthersDoNot() {
        val mail = AppItem("com.mail", "com.mail.Main", Process.myUserHandle(), 0, "Mail")
        val vault = AppItem("com.vault", "com.vault.Main", Process.myUserHandle(), 12, "Vault")
        compose.setContent {
            ExpressiveTheme {
                val haptics = LocalHapticFeedback.current
                val drag = remember { HomeDragController() }
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.size(320.dp, 300.dp)) {
                    appCells(listOf(mail), emptyMap(), true, 1f, true, 56.dp, {}, { _, _ -> }, drag, {}, { _, _, _, _ -> }, { _, _ -> }, haptics, false)
                    appCells(listOf(vault), emptyMap(), true, 1f, true, 56.dp, {}, { _, _ -> }, drag, {}, { _, _, _, _ -> }, { _, _ -> }, haptics, false,
                        liftable = false, privateMark = true)
                }
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithTag(PRIVATE_APP_MARK_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun thePrivateSpacesSearchRowSaysItsNameAndTakesATap() {
        var opened = 0
        compose.setContent { ExpressiveTheme { PrivateSpaceEntryRow(onOpen = { opened++ }) } }

        compose.onNodeWithText("Private space").assertIsDisplayed().performClick()

        assertThat(opened).isEqualTo(1)
    }

    @Test fun withTheLabelsOffTheInstallTileStillSaysWhatItIs() {
        compose.setContent {
            ExpressiveTheme {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.size(320.dp, 300.dp)) {
                    privateInstallTile(iconSize = 56.dp, showLabel = false, onInstall = {})
                }
            }
        }

        compose.onNodeWithText("Install").assertDoesNotExist()
        compose.onNodeWithContentDescription("Install").assertIsDisplayed()
    }
}
