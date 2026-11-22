package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tick counts that put the thumb on round steps. */
class ScaleSliderStepsTest {
    @Test fun fivePercentStepsAcrossEachSizeRange() {
        assertThat(scaleSliderSteps(0.8f..1.3f, 0.05f)).isEqualTo(9)
        assertThat(scaleSliderSteps(0.8f..1.6f, 0.05f)).isEqualTo(15)
        assertThat(scaleSliderSteps(0.6f..1.8f, 0.05f)).isEqualTo(23)
    }

    @Test fun tenPercentStepsAcrossTheLongPressRange() {
        assertThat(scaleSliderSteps(0.5f..2.0f, 0.1f)).isEqualTo(14)
    }

    @Test fun aMultiplierReadsAsAWholePercent() {
        assertThat(scalePercent(1.149f)).isEqualTo(115)
        assertThat(scalePercent(0.8f)).isEqualTo(80)
        assertThat(scalePercent(1f)).isEqualTo(100)
    }
}

private class RecordingHaptics : HapticFeedback {
    val felt = mutableListOf<HapticFeedbackType>()
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        felt += hapticFeedbackType
    }
}

/** The slider row: readout, snapping with a felt tick, and the way back to the default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ScaleSliderRowTest {
    @get:Rule val compose = createComposeRule()

    private val changes = mutableListOf<Float>()
    private val haptics = RecordingHaptics()
    private val felt get() = haptics.felt

    /** The stored setting as the screen sees it: every reported change is written straight back. */
    private var value by mutableStateOf(1f)

    private fun setRow(initial: Float, feedBack: Boolean = true) {
        value = initial
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                ScaleSliderRow(
                    label = "Icon size",
                    value = value,
                    onValueChange = { changes += it; if (feedBack) value = it },
                    valueRange = 0.8f..1.3f,
                    valueText = { "${scalePercent(it)}%" },
                )
            }
        }
    }

    private val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    @Test fun theValueShowsAsAWholePercentBesideTheLabel() {
        setRow(1.149f)
        compose.onNodeWithText("115%").assertIsDisplayed()
    }

    @Test fun atTheDefaultThereIsNothingToReset() {
        setRow(1f)
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithText("Default 100%").assertDoesNotExist()
    }

    @Test fun theDefaultChipBringsTheValueStraightBack() {
        setRow(1.15f)
        compose.onNodeWithText("Default 100%").performClick()
        assertThat(changes).containsExactly(1f)
        assertThat(felt).containsExactly(HapticFeedbackType.SegmentTick)
        compose.onNodeWithText("Default 100%").assertDoesNotExist()
    }

    /** The screen composes with the defaults before the stored settings load: the row must not keep
     *  judging ticks against that first readout once the real value has arrived. */
    @Test fun theDefaultChipStillTicksAfterTheStoredValueLoads() {
        setRow(1f)
        compose.runOnIdle { value = 1.15f }
        compose.onNodeWithText("Default 100%").performClick()
        assertThat(changes).containsExactly(1f)
        assertThat(felt).containsExactly(HapticFeedbackType.SegmentTick)
    }

    @Test fun theSliderSnapsToFivePercentAndTicksOnce() {
        setRow(1f)
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(1.13f) }
        compose.waitForIdle()
        assertThat(changes).hasSize(1)
        assertThat(changes.single()).isWithin(1e-4f).of(1.15f)
        assertThat(felt).containsExactly(HapticFeedbackType.SegmentTick)
    }

    /** While a finger lingers on one step the slider may report the same snapped value again, and the
     *  stored setting lags behind the gesture: one tick per step, never one per report. */
    @Test fun aDragThatLingersOnAStepTicksOncePerStep() {
        setRow(1f, feedBack = false)
        compose.onNode(slider).performTouchInput {
            down(Offset(width * 0.42f, centerY))
            repeat(12) { moveBy(Offset(width * 0.016f, 0f)) }
            up()
        }
        compose.waitForIdle()
        // One tick per change of readout along the reported sequence, starting from what was shown.
        val readouts = listOf(100) + changes.map { scalePercent(it) }
        val visibleChanges = readouts.zipWithNext().count { (before, after) -> before != after }
        assertThat(visibleChanges).isGreaterThan(1)
        assertThat(felt).hasSize(visibleChanges)
    }
}

/** A narrow screen with a doubled font and a long translation must still show the label and the value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es")
class ScaleSliderRowNarrowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, so a long translation really is wide
    fun theLabelAndTheReadoutKeepTheirWidthBesideTheDefaultChip() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    ScaleSliderRow(
                        label = "Tamaño de los iconos",
                        value = 1.15f,
                        onValueChange = {},
                        valueRange = 0.8f..1.3f,
                        valueText = { "${scalePercent(it)} %" },
                    )
                }
            }
        }
        compose.onNodeWithText("Tamaño de los iconos").assertIsDisplayed().assertWidthIsAtLeast(40.dp)
        compose.onNodeWithText("115 %").assertIsDisplayed().assertWidthIsAtLeast(20.dp)
        compose.onNodeWithText("Predeterminado 100 %").assertIsDisplayed()
    }
}
