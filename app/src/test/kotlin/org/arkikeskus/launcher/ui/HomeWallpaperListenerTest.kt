package org.arkikeskus.launcher.ui

import android.app.Application
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.os.Handler
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.WallpaperLook
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HomeWallpaperListenerTest.Colours::class])
class HomeWallpaperListenerTest {
    @get:Rule val compose = createComposeRule()

    @Implements(WallpaperManager::class)
    class Colours {
        @Implementation fun addOnColorsChangedListener(callback: WallpaperManager.OnColorsChangedListener, handler: Handler?) {
            listener = callback
        }
        @Implementation fun removeOnColorsChangedListener(callback: WallpaperManager.OnColorsChangedListener) {
            if (listener === callback) listener = null
        }
        @Implementation fun getWallpaperColors(which: Int): WallpaperColors? {
            releaseRead.await(10, TimeUnit.SECONDS)
            return null
        }
        companion object {
            var listener: WallpaperManager.OnColorsChangedListener? = null
            val releaseRead = CountDownLatch(1)
        }
    }

    @Test fun homeStartsFromDiskAndOnlySystemWallpaperAnnouncementsChangeItsColour() {
        val stored = WallpaperLook(true, -1)
        val frames = mutableListOf<WallpaperLook>()
        try {
            compose.setContent {
                val look = rememberWallpaperLook(stored).look
                SideEffect { frames += look }
            }
            compose.runOnIdle {
                assertThat(frames.first()).isEqualTo(stored)
                checkNotNull(Colours.listener).onColorsChanged(null, WallpaperManager.FLAG_LOCK)
            }
            compose.runOnIdle {
                assertThat(frames.last()).isEqualTo(stored)
                checkNotNull(Colours.listener).onColorsChanged(null, WallpaperManager.FLAG_SYSTEM)
            }
            compose.runOnIdle { assertThat(frames.last()).isEqualTo(WallpaperLook()) }
        } finally {
            Colours.releaseRead.countDown()
        }
    }
}
