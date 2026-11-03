package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The user's own picture behind the drawer is blurred inside the window by the blur setting and its strength. */
class DrawerWallpaperBlurTest {

    @Test fun theBlurFollowsTheSettingAndItsStrength() {
        assertThat(drawerWallpaperBlur(enabled = false, strength = 1f)).isEqualTo(0.dp)
        assertThat(drawerWallpaperBlur(enabled = true, strength = 1f)).isEqualTo(DRAWER_PICTURE_BLUR)
        assertThat(drawerWallpaperBlur(enabled = true, strength = 0.5f)).isEqualTo(DRAWER_PICTURE_BLUR / 2)
        assertThat(drawerWallpaperBlur(enabled = true, strength = 2f)).isEqualTo(DRAWER_PICTURE_BLUR * 2)
        assertThat(drawerWallpaperBlur(enabled = true, strength = -1f)).isEqualTo(0.dp)
        assertThat(DRAWER_PICTURE_BLUR).isEqualTo(24.dp)
    }
}
