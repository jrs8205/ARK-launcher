package org.arkikeskus.launcher

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Which windows are translucent. The HOME window must be (the decor draws the wallpaper blur behind
 * the rising drawer only for a translucent window); the Settings window must NOT be: a translucent
 * Settings left the launcher started underneath, so its foreground flag never reset and the next HOME
 * press jumped to the home page instead of staying where the user was (a review finding).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowThemesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun windowIsTranslucent(style: Int): Boolean {
        val a = context.obtainStyledAttributes(style, intArrayOf(android.R.attr.windowIsTranslucent))
        try {
            return a.getBoolean(0, false)
        } finally {
            a.recycle()
        }
    }

    private fun postSplashTheme(style: Int): Int {
        val a = context.obtainStyledAttributes(style, intArrayOf(androidx.core.splashscreen.R.attr.postSplashScreenTheme))
        try {
            return a.getResourceId(0, 0)
        } finally {
            a.recycle()
        }
    }

    @Test fun theSettingsWindowStaysOpaque() {
        assertThat(windowIsTranslucent(R.style.Theme_ArkikeskusLauncher)).isFalse()
    }

    @Test fun theHomeWindowIsTranslucentForTheDrawerBlur() {
        assertThat(windowIsTranslucent(R.style.Theme_ArkikeskusLauncher_Home)).isTrue()
    }

    @Test fun theSplashHandsOffToTheHomeTheme() {
        assertThat(postSplashTheme(R.style.Theme_ArkikeskusLauncher_Starting)).isEqualTo(R.style.Theme_ArkikeskusLauncher_Home)
    }
}
