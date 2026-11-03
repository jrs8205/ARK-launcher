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
 * The blur strength (a Telegram request after 0.9.9) reads and moves like the other scale sliders: a
 * percent beside the label, 5 % steps felt as ticks, 10–200 % of the 0.9.9 blur, and a chip back to 100 %.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerBlurStrengthRowTest {
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
                DrawerBlurStrengthRow(value = value, onValueChange = { picked += it })
            }
        }
        compose.waitForIdle()
    }

    private fun percent(p: Int) = res.getString(R.string.settings_percent, p)

    private fun defaultChip() = res.getString(R.string.settings_reset_default, percent(100))

    @Test fun theDefaultIsTheNominalBlurWithoutAResetChip() {
        show(1f)

        compose.onNodeWithText(res.getString(R.string.settings_blur_strength)).assertIsDisplayed()
        compose.onNodeWithText(percent(100)).assertIsDisplayed()
        compose.onNodeWithText(defaultChip()).assertDoesNotExist()
    }

    /** The slider snaps to 5 % steps: a nudge to 112 % lands on 110 %. */
    @Test fun theValueSnapsToFivePercentSteps() {
        show(1f)

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.12f) }

        assertThat(picked.single()).isWithin(0.001f).of(1.10f)
        assertThat(felt).containsExactly(HapticFeedbackType.SegmentTick)
    }

    /** The range is 10–200 %: a push past the top stops at 200 %, a push past the bottom at 10 % (the owner found
     *  25 % still too strong to make out the wallpaper). */
    @Test fun theRangeRunsFromATenthToTwiceTheBlur() {
        show(1f)

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }

        assertThat(picked).hasSize(2)
        assertThat(picked[0]).isWithin(0.001f).of(2f)
        assertThat(picked[1]).isWithin(0.001f).of(0.1f)
    }

    @Test fun offTheDefaultTheChipBringsItBack() {
        show(0.5f)

        compose.onNodeWithText(percent(50)).assertIsDisplayed()
        compose.onNodeWithText(defaultChip()).performClick()

        assertThat(picked).containsExactly(1f)
    }
}
