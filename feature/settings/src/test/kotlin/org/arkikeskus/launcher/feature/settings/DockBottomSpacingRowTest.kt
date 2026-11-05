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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Issue #24: "Space below the dock" reads in dp beside its label, moves in 4 dp steps felt as ticks,
 * and offers a chip back to the default of none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DockBottomSpacingRowTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val picked = mutableListOf<Int>()

    private val felt = mutableListOf<HapticFeedbackType>()
    private val haptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            felt += hapticFeedbackType
        }
    }

    private fun show(value: Int) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                DockBottomSpacingRow(value = value, onValueChange = { picked += it })
            }
        }
        compose.waitForIdle()
    }

    private fun dp(value: Int) = res.getString(R.string.settings_dp, value)

    private fun defaultChip() = res.getString(R.string.settings_reset_default, dp(0))

    @Test fun theDefaultReadsZeroWithoutAResetChip() {
        show(0)

        compose.onNodeWithText(res.getString(R.string.settings_dock_bottom_space)).assertIsDisplayed()
        compose.onNodeWithText(dp(0)).assertIsDisplayed()
        compose.onNodeWithText(defaultChip()).assertDoesNotExist()
    }

    /** The slider snaps to 4 dp steps: a nudge to 22 dp lands on 24 dp. */
    @Test fun theValueSnapsToFourDpSteps() {
        show(0)

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(22f) }

        assertThat(picked).containsExactly(24)
        assertThat(felt).containsExactly(HapticFeedbackType.SegmentTick)
    }

    @Test fun offTheDefaultTheChipBringsItBack() {
        show(32)

        compose.onNodeWithText(dp(32)).assertIsDisplayed()
        compose.onNodeWithText(defaultChip()).performClick()

        assertThat(picked).containsExactly(0)
    }
}
