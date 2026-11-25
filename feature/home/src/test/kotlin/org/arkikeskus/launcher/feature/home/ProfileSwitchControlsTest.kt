package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.HomeProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ProfileSwitchControlsTest {
    @get:Rule val compose = createComposeRule()
    private val profiles = listOf(HomeProfile(3, "A"), HomeProfile(1, "B"), HomeProfile(2, "C"))

    @Test fun thePickerOffersASingleCellSwitchOnlyWithAnotherProfile() {
        val offer = mutableStateOf(false)
        val controller = WidgetDragController(org.arkikeskus.launcher.ui.HomeDragController())
        var selection: Triple<WidgetChoice, Int, Int>? = null
        compose.setContent {
            WidgetPickerScreen(controller, 4, 5, { item, x, y -> selection = Triple(item, x, y) }, {}, offerProfileSwitch = offer.value)
        }
        compose.onNodeWithText("Switch profile").assertDoesNotExist()
        offer.value = true
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Switch profile")
        compose.onNodeWithText("Add").performClick()
        assertThat(selection).isEqualTo(Triple(WidgetChoice.Builtin("profile_switch"), 1, 1))
    }

    @Test fun lockingHidesWidgetsButTheProfilesMenuStillOpensWithTwoProfiles() {
        val count = mutableStateOf(1)
        val locked = mutableStateOf(true)
        var options = emptyList<org.arkikeskus.launcher.ui.IconMenuItem>()
        var opened = false
        compose.setContent {
            val items = homeWidgetAndProfileOptions(locked.value, count.value, {}, { opened = true })
            androidx.compose.runtime.SideEffect { options = items }
        }
        compose.runOnIdle { assertThat(options).isEmpty(); count.value = 2 }
        compose.runOnIdle {
            assertThat(options.map { it.label }).containsExactly("Profiles")
            options.single().onClick()
            assertThat(opened).isTrue()
            locked.value = false
        }
        compose.runOnIdle { assertThat(options.map { it.label }).containsExactly("Widgets", "Profiles").inOrder() }
    }

    @Test fun theMenuMarksTheCurrentProfileAndOpensManagement() {
        var picked = 0L
        var managed = false
        compose.setContent {
            ProfileChoices(profiles, 1, adding = false, onPick = { picked = it }, onManage = { managed = true }, onDismiss = {})
        }
        compose.onNodeWithText("In use").assertIsDisplayed()
        compose.onNodeWithText("B").assertIsNotEnabled()
        compose.onNodeWithText("C").performClick()
        assertThat(picked).isEqualTo(2L)
        compose.onNodeWithText("Manage profiles").performClick()
        assertThat(managed).isTrue()
    }

    @Test fun theTargetQuestionOffersOnlyOtherProfilesAndCancelChoosesNothing() {
        var picked = 0L
        var dismissed = false
        compose.setContent {
            ProfileChoices(profiles, 1, adding = true, onPick = { picked = it }, onManage = {}, onDismiss = { dismissed = true })
        }
        compose.onNodeWithText("B").assertDoesNotExist()
        compose.onNodeWithText("A").assertIsDisplayed()
        compose.onNodeWithText("Cancel", ignoreCase = true).performClick()
        assertThat(dismissed).isTrue()
        assertThat(picked).isEqualTo(0L)
        compose.onNodeWithText("C").performClick()
        assertThat(picked).isEqualTo(2L)
    }

    @Test fun anUppercaseExpansionStillFitsOneGraphemeOnTheIcon() {
        compose.setContent {
            ProfileSwitchIcon(HomeProfile(2, "ßeta"), Modifier, 48.dp, true, Color.Gray, {}, {})
        }
        compose.onNodeWithText("S", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aRenamedTargetChangesItsWholeInitialAndAMissingTargetOnlyReportsItsAbsence() {
        val target = mutableStateOf<HomeProfile?>(HomeProfile(2, "äiti"))
        var switched = 0L
        var missing = false
        compose.setContent {
            ProfileSwitchIcon(target.value, Modifier, 48.dp, true, Color.Gray, { switched = it }, { missing = true })
        }
        compose.onNodeWithText("Ä", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to profile äiti").performClick()
        assertThat(switched).isEqualTo(2L)
        val emoji = "👩‍🔧"
        target.value = HomeProfile(2, emoji + " Work")
        compose.onNodeWithText(emoji, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(emoji + " Work", useUnmergedTree = true).assertIsDisplayed()
        switched = 0
        target.value = null
        compose.onNodeWithContentDescription("This profile no longer exists.").performClick()
        assertThat(missing).isTrue()
        assertThat(switched).isEqualTo(0L)
    }
}
