package org.arkikeskus.launcher.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the app drawer paints over what is behind it (Settings > App drawer, the owner's 1.0.0 notes): a colour at
 * its opacity; "Transparent" paints nothing; the user's own picture turns the colour off and takes a black dim of
 * its own instead, so only the picture shows unless the user dims it for legibility.
 */
class DrawerSurfaceTest {
    private val blue = 0xFF42A5F5.toInt()
    private val black = 0xFF000000.toInt()

    @Test fun aColourPaintsItselfAtItsOpacity() {
        assertThat(drawerSurface(color = blue, colorOpacity = 0.6f, hasPicture = false, pictureDim = 0.5f))
            .isEqualTo(DrawerSurface(tint = blue, paint = blue, opacity = 0.6f))
    }

    @Test fun theThemeIsNoColourAtTheSameOpacity() {
        assertThat(drawerSurface(color = null, colorOpacity = 1f, hasPicture = false, pictureDim = 0f))
            .isEqualTo(DrawerSurface(tint = null, paint = null, opacity = 1f))
    }

    @Test fun transparentPaintsNothingAndLeavesTheThemeToTheChips() {
        val surface = drawerSurface(LauncherSettings.DRAWER_COLOR_TRANSPARENT, colorOpacity = 1f, hasPicture = false, pictureDim = 0f)
        assertThat(surface).isEqualTo(DrawerSurface(tint = null, paint = null, opacity = 0f))
    }

    @Test fun aPictureTurnsTheColourOffAndDimsWithBlack() {
        assertThat(drawerSurface(color = blue, colorOpacity = 1f, hasPicture = true, pictureDim = 0.3f))
            .isEqualTo(DrawerSurface(tint = null, paint = black, opacity = 0.3f))
        // Out of the box the picture shows undimmed, whatever the colour and its opacity say.
        val undimmed = drawerSurface(color = blue, colorOpacity = 1f, hasPicture = true, pictureDim = LauncherSettings.DEFAULT_DRAWER_PICTURE_DIM)
        assertThat(undimmed.opacity).isEqualTo(0f)
    }

    @Test fun opacitiesAreClamped() {
        assertThat(drawerSurface(blue, colorOpacity = 2f, hasPicture = false, pictureDim = 0f).opacity).isEqualTo(1f)
        assertThat(drawerSurface(blue, colorOpacity = 1f, hasPicture = true, pictureDim = -1f).opacity).isEqualTo(0f)
    }
}
