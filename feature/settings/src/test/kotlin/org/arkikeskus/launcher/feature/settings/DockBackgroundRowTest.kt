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
 * The dock background darkness reads and moves like the status bar's: a percent of black beside the
 * label, 5 % steps felt as ticks, and a chip back to the default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DockBackgroundRowTest {
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
                DockBackgroundRow(value = value, onValueChange = { picked += it })
            }
        }
        compose.waitForIdle()
    }

    private fun percent(p: Int) = res.getString(R.string.settings_percent, p)

    private fun defaultChip() = res.getString(R.string.settings_reset_default, percent(35))

    @Test fun theDefaultReadsThirtyFivePercentWithoutAResetChip() {
        show(LauncherSettings.DEFAULT_DOCK_BACKGROUND)

        compose.onNodeWithText(percent(35)).assertIsDisplayed()
        compose.onNodeWithText(defaultChip()).assertDoesNotExist()
    }

    /** The slider snaps to 5 % steps: a nudge to 37 % lands on 35 %, one to 38 % on 40 %. */
    @Test fun theValueSnapsToFivePercentSteps() {
        show(LauncherSettings.DEFAULT_DOCK_BACKGROUND)

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.38f) }

        assertThat(picked.single()).isWithin(0.001f).of(0.40f)
        assertThat(felt).containsExactly(HapticFeedbackType.SegmentTick)
    }

    @Test fun offTheDefaultTheChipBringsItBack() {
        show(0.7f)

        compose.onNodeWithText(percent(70)).assertIsDisplayed()
        compose.onNodeWithText(defaultChip()).performClick()

        assertThat(picked).containsExactly(LauncherSettings.DEFAULT_DOCK_BACKGROUND)
    }
}
