package org.arkikeskus.launcher.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The glass layer between the wallpaper and the home screen (the owner's 1.0.5 idea): a white tint over
 * the whole screen, stronger at the top than at the bottom, both following the strength setting.
 */
class GlassTintTest {
    @Test fun theTintFollowsTheStrengthAndFadesDownwards() {
        val soft = glassTint(0.1f)
        val strong = glassTint(0.5f)
        assertThat(soft.top).isGreaterThan(soft.bottom)
        assertThat(strong.top).isGreaterThan(soft.top)
        assertThat(strong.bottom).isGreaterThan(soft.bottom)
        assertThat(glassTint(5f).top).isAtMost(1f)
        assertThat(glassTint(0f)).isEqualTo(GlassTint(0f, 0f))
    }

    @Test fun theTintFadesEvenlyFromTheTopToTheBottom() {
        val tint = GlassTint(top = 0.6f, bottom = 0.2f)
        assertThat(tint.at(0f)).isEqualTo(0.6f)
        assertThat(tint.at(1f)).isWithin(0.0001f).of(0.2f)
        assertThat(tint.at(0.5f)).isWithin(0.0001f).of(0.4f)
        assertThat(tint.at(-1f)).isEqualTo(0.6f)
        assertThat(tint.at(7f)).isWithin(0.0001f).of(0.2f)
    }
}
