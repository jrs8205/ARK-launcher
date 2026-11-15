package org.arkikeskus.launcher.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How full the battery glyph is drawn: its level, and while charging a sweep from the level up to full. */
class BatteryFillTest {
    @Test
    fun `without a sweep the glyph shows the level`() {
        assertThat(batteryFillFraction(40, 0f)).isWithin(1e-6f).of(0.4f)
        assertThat(batteryFillFraction(0, 0f)).isEqualTo(0f)
        assertThat(batteryFillFraction(100, 0f)).isEqualTo(1f)
    }

    @Test
    fun `the sweep runs from the level to full, never below the level`() {
        assertThat(batteryFillFraction(40, 0.5f)).isWithin(1e-6f).of(0.7f)
        assertThat(batteryFillFraction(40, 1f)).isWithin(1e-6f).of(1f)
        for (step in 0..10) assertThat(batteryFillFraction(40, step / 10f)).isAtLeast(0.4f)
    }

    @Test
    fun `a full battery has nothing left to sweep`() {
        for (step in 0..10) assertThat(batteryFillFraction(100, step / 10f)).isEqualTo(1f)
    }

    @Test
    fun `a level or a sweep outside its range is brought inside it`() {
        assertThat(batteryFillFraction(140, 0f)).isEqualTo(1f)
        assertThat(batteryFillFraction(-5, 0f)).isEqualTo(0f)
        assertThat(batteryFillFraction(40, 3f)).isWithin(1e-6f).of(1f)
        assertThat(batteryFillFraction(40, -1f)).isWithin(1e-6f).of(0.4f)
    }
}
