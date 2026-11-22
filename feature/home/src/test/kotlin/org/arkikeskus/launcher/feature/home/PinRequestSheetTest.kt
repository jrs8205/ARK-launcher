package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.DefaultPaletteSeed
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The confirmation sheet another app's "Add to home screen" opens, in each of its states. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PinRequestSheetTest {
    @get:Rule val compose = createComposeRule()

    private var added = 0
    private var cancelled = 0

    private val confirm = PinRequestUiState.Confirm(
        appLabel = "Chrome", label = "Daily News", icon = null, spanX = 1, spanY = 1,
    )

    private fun show(state: PinRequestUiState, darkTheme: Boolean = false) {
        compose.setContent {
            LauncherTheme(palette = basePalette(DefaultPaletteSeed, darkTheme)) {
                PinRequestSheet(state = state, onAdd = { added++ }, onCancel = { cancelled++ })
            }
        }
        compose.waitForIdle()
    }

    @Test fun theNormalStateShowsTheAppTheItemItsSizeAndBothButtons() {
        show(confirm)

        compose.onNodeWithText("Chrome").assertIsDisplayed()
        compose.onNodeWithText("Will be added to the first free spot.").assertIsDisplayed()
        compose.onNodeWithText("Daily News").assertIsDisplayed()
        compose.onNodeWithText("1 × 1").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Add to home screen").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun cancelAcceptsNothing() {
        show(confirm)

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertThat(cancelled).isEqualTo(1)
        assertThat(added).isEqualTo(0)
    }

    @Test fun addToHomeScreenAccepts() {
        show(confirm)

        compose.onNodeWithText("Add to home screen").performClick()
        compose.waitForIdle()

        assertThat(added).isEqualTo(1)
        assertThat(cancelled).isEqualTo(0)
    }

    @Test fun whileAnAcceptIsUnderWayBothButtonsAreDisabled() {
        show(confirm.copy(busy = true))

        compose.onNodeWithText("Add to home screen").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test fun theLockedStateExplainsAndOffersOnlyClose() {
        show(PinRequestUiState.Locked)

        compose.onNodeWithText("The desktop is locked").assertIsDisplayed()
        compose.onNodeWithText("Turn off Lock desktop in ARK-Launcher's settings to add items.").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Add to home screen").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test fun closeOnTheLockedStateAcceptsNothing() {
        show(PinRequestUiState.Locked)

        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()

        assertThat(cancelled).isEqualTo(1)
        assertThat(added).isEqualTo(0)
    }

    @Test fun aFullHomeScreenExplainsAndOffersOnlyClose() {
        show(PinRequestUiState.NoSpace)

        compose.onNodeWithText("The home screen is full").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Add to home screen").assertDoesNotExist()
    }

    @Test fun rendersInTheLightTheme() {
        show(confirm, darkTheme = false)

        compose.onNodeWithText("Chrome").assertIsDisplayed()
        compose.onNodeWithText("Add to home screen").assertIsDisplayed()
    }

    @Test fun rendersInTheDarkTheme() {
        show(confirm, darkTheme = true)

        compose.onNodeWithText("Chrome").assertIsDisplayed()
        compose.onNodeWithText("Add to home screen").assertIsDisplayed()
    }

    /** Where the first line of [text] sits across the sheet's window: the line's centre as a
     *  fraction of the window's width (the sheet spans it, so 0.5 means centred). */
    private fun lineCentreFraction(text: String): Float {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        val results = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.first()
        val lineCentre = node.boundsInRoot.left + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f
        return lineCentre / node.root!!.semanticsOwner.rootSemanticsNode.size.width
    }

    @Test fun theTitleAndTheSubtitleAreCentred() {
        show(confirm)

        assertThat(lineCentreFraction("Chrome")).isWithin(0.02f).of(0.5f)
        assertThat(lineCentreFraction("Will be added to the first free spot.")).isWithin(0.02f).of(0.5f)
    }

    // --- app widgets ---

    private val clockInfo = AppWidgetProviderInfo().also { it.provider = ComponentName("com.clock", "Analog") }

    private val widgetConfirm = PinRequestUiState.Confirm(
        appLabel = "Clock", label = "Analog clock", icon = null, spanX = 4, spanY = 2,
        widget = WidgetPinPreview(clockInfo, widthDp = 280f, heightDp = 200f),
    )

    @Test fun aWidgetCardShowsThePreviewTheNameAndTheSizeWithBothButtons() {
        show(widgetConfirm)

        compose.onNodeWithText("Clock").assertIsDisplayed()
        compose.onNodeWithTag(PIN_WIDGET_PREVIEW_TAG).assertIsDisplayed()
        compose.onNodeWithText("Analog clock").assertIsDisplayed()
        compose.onNodeWithText("4 × 2").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Add to home screen").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun aShortcutCardHasNoWidgetPreview() {
        show(confirm)

        compose.onNodeWithTag(PIN_WIDGET_PREVIEW_TAG).assertDoesNotExist()
    }

    @Test fun aWorkProfileWidgetExplainsAndOffersOnlyClose() {
        show(PinRequestUiState.WorkProfile)

        compose.onNodeWithText("Work profile widgets can't be added yet.").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Add to home screen").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test fun aWidgetTooBigForTheGridExplainsAndOffersOnlyClose() {
        show(PinRequestUiState.TooBig)

        compose.onNodeWithText("This widget is too big for the home screen.").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Add to home screen").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test fun closeOnTheTooBigStateAcceptsNothing() {
        show(PinRequestUiState.TooBig)

        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()

        assertThat(cancelled).isEqualTo(1)
        assertThat(added).isEqualTo(0)
    }
}
