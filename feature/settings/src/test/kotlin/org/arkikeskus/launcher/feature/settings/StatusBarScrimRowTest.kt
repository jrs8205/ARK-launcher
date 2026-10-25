package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The status bar background darkness reads as a percent of black, as the owner asked: 0 % is
 * transparent, 50 % half black, 100 % solid black — with a way back to the default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class StatusBarScrimRowTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val picked = mutableListOf<Float>()

    private val felt = mutableListOf<HapticFeedbackType>()
    private val haptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            felt += hapticFeedbackType
        }
    }

    private fun show(value: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                StatusBarScrimRow(value = value, onValueChange = { picked += it })
            }
        }
        compose.waitForIdle()
    }

    private fun percent(p: Int) = res.getString(R.string.settings_percent, p)

    private fun defaultChip() = res.getString(R.string.settings_reset_default, percent(60))

    @Test fun theDefaultReadsSixtyPercentWithoutAResetChip() {
        show(LauncherSettings.DEFAULT_STATUS_BAR_SCRIM)

        compose.onNodeWithText(percent(60)).assertIsDisplayed()
        compose.onNodeWithText(defaultChip()).assertDoesNotExist()
    }

    @Test fun transparentReadsZeroPercent() {
        show(0f)

        compose.onNodeWithText(percent(0)).assertIsDisplayed()
    }

    @Test fun solidBlackReadsAHundredPercent() {
        show(1f)

        compose.onNodeWithText(percent(100)).assertIsDisplayed()
    }

    /** Like the icon size slider: a step to a new percent is felt as one tick. */
    @Test fun aStepToANewPercentTicks() {
        show(LauncherSettings.DEFAULT_STATUS_BAR_SCRIM)

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }

        assertThat(felt).containsExactly(HapticFeedbackType.SegmentTick)
        assertThat(picked.single()).isWithin(0.001f).of(0.7f)
    }

    @Test fun offTheDefaultTheChipBringsItBack() {
        show(0.25f)

        compose.onNodeWithText(defaultChip()).performClick()

        assertThat(picked).containsExactly(LauncherSettings.DEFAULT_STATUS_BAR_SCRIM)
    }
}
