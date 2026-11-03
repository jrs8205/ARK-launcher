package org.arkikeskus.launcher.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A Telegram request: the wallpaper blurs as the drawer rises. The window's background blur radius
 * follows the drawer's openness (0 closed, 1 open) up to the chosen maximum, in whole pixels, and is
 * exactly zero while the drawer is parked so a closed drawer never leaves a faint blur behind.
 */
class DrawerWallpaperBlurTest {

    @Test fun aParkedDrawerLeavesNoBlur() {
        assertThat(drawerBlurRadiusPx(0f, maxPx = 60)).isEqualTo(0)
        assertThat(drawerBlurRadiusPx(0.0005f, maxPx = 60)).isEqualTo(0)
    }

    @Test fun anOpenDrawerBlursTheMost() {
        assertThat(drawerBlurRadiusPx(1f, maxPx = 60)).isEqualTo(60)
        assertThat(drawerBlurRadiusPx(1.2f, maxPx = 60)).isEqualTo(60)
    }

    @Test fun theBlurGrowsWithTheDrawerInWholePixels() {
        assertThat(drawerBlurRadiusPx(0.5f, maxPx = 60)).isEqualTo(30)
        assertThat(drawerBlurRadiusPx(0.25f, maxPx = 63)).isEqualTo(16)
        val samples = (0..20).map { drawerBlurRadiusPx(it / 20f, maxPx = 60) }
        assertThat(samples).isInOrder()
    }

    @Test fun theBlurIsOffWhenTheSettingIsOrTheSystemIsTooOld() {
        assertThat(drawerBlurRadiusPx(1f, maxPx = 60, enabled = false)).isEqualTo(0)
        assertThat(drawerBlurRadiusPx(1f, maxPx = 60, enabled = true, supported = false)).isEqualTo(0)
    }

    /** The strength slider (a Telegram request) scales the maximum: 100 % is the 0.9.9 blur, 50 % half of
     *  it, 200 % twice, in whole pixels and never below one pixel for a non-zero strength. */
    @Test fun theStrengthSettingScalesTheMaximum() {
        assertThat(drawerBlurMaxPx(basePx = 60, strength = 1f)).isEqualTo(60)
        assertThat(drawerBlurMaxPx(basePx = 60, strength = 0.5f)).isEqualTo(30)
        assertThat(drawerBlurMaxPx(basePx = 60, strength = 2f)).isEqualTo(120)
        assertThat(drawerBlurMaxPx(basePx = 63, strength = 0.25f)).isEqualTo(16)
        assertThat(drawerBlurMaxPx(basePx = 1, strength = 0.25f)).isEqualTo(1)
        assertThat(drawerBlurRadiusPx(1f, maxPx = drawerBlurMaxPx(60, 0.5f))).isEqualTo(30)
    }
}
