package org.arkikeskus.launcher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IconPressTest {
    private val icon = IntSize(200, 240)
    private val slop = 20f

    @Test fun aHoldSurvivesFingerRollAnywhereOnTheIcon() {
        assertThat(IconPress.staysOnIcon(Offset(5f, 230f), icon, slop)).isTrue()
        assertThat(IconPress.staysOnIcon(Offset(-19f, 255f), icon, slop)).isTrue()
    }

    @Test fun aHoldEndsOnceTheFingerLeavesTheIconPlusSlop() {
        assertThat(IconPress.staysOnIcon(Offset(-21f, 100f), icon, slop)).isFalse()
        assertThat(IconPress.staysOnIcon(Offset(100f, 261f), icon, slop)).isFalse()
    }

    @Test fun aLiftedIconStartsDraggingOnlyPastTheThreshold() {
        val origin = Offset(100f, 100f)
        assertThat(IconPress.startsDrag(Offset(130f, 120f), origin, thresholdPx = 42f)).isFalse()
        assertThat(IconPress.startsDrag(Offset(143f, 100f), origin, thresholdPx = 42f)).isTrue()
    }

    @Test fun jitterThatReturnsToTheOriginNeverStartsADrag() {
        val origin = Offset(100f, 100f)
        val wobble = listOf(Offset(110f, 95f), Offset(92f, 108f), Offset(105f, 102f), Offset(99f, 97f))
        assertThat(wobble.any { IconPress.startsDrag(it, origin, thresholdPx = 42f) }).isFalse()
    }
}
