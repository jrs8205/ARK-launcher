package org.arkikeskus.launcher.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The user's own picture behind the app drawer: saved as a file of its own with its mean lightness,
 * a new pick replaces the old file, a clear removes it, and a fresh store finds what the last one
 * left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerWallpaperStoreTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val dir = File(context.filesDir, "drawer_wallpaper")

    private fun picture(color: Int, width: Int = 40, height: Int = 20): Bitmap =
        createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun theMeanLuminanceIsTheWholePicturesLightness() {
        assertThat(meanLuminance(IntArray(4) { Color.WHITE })).isWithin(0.001f).of(1f)
        assertThat(meanLuminance(IntArray(4) { Color.BLACK })).isWithin(0.001f).of(0f)
        assertThat(meanLuminance(intArrayOf(Color.WHITE, Color.BLACK))).isWithin(0.001f).of(0.5f)
        assertThat(meanLuminance(IntArray(0))).isEqualTo(0f)
    }

    @Test fun theSampleSizeKeepsTheLongSideWithinTheLimit() {
        assertThat(sampleSizeFor(1000, 800, 1600)).isEqualTo(1)
        assertThat(sampleSizeFor(4000, 3000, 1600)).isEqualTo(3)
        assertThat(sampleSizeFor(3000, 4000, 1600)).isEqualTo(3)
        assertThat(sampleSizeFor(0, 0, 1600)).isEqualTo(1)
    }

    @Test fun aPictureIsSavedWithItsLightnessAndReplacesTheLastOne() = runTest {
        val store = DrawerWallpaperStore(context)
        assertThat(store.current.value).isNull()

        store.set(picture(Color.WHITE))
        val first = store.current.value!!
        assertThat(first.file.isFile).isTrue()
        assertThat(first.file.parentFile).isEqualTo(dir)
        assertThat(first.luminance).isWithin(0.01f).of(1f)

        store.set(picture(Color.BLACK))
        val second = store.current.value!!
        assertThat(second.file).isNotEqualTo(first.file) // a new name, so no cache shows the old one
        assertThat(first.file.exists()).isFalse()
        assertThat(second.luminance).isWithin(0.01f).of(0f)

        // A fresh store (the next launcher start) finds the picture and its lightness.
        val next = DrawerWallpaperStore(context)
        assertThat(next.current.value).isEqualTo(second)
    }

    @Test fun aClearRemovesThePictureForGood() = runTest {
        val store = DrawerWallpaperStore(context)
        store.set(picture(Color.GRAY))
        val file = store.current.value!!.file

        store.clear()

        assertThat(store.current.value).isNull()
        assertThat(file.exists()).isFalse()
        assertThat(DrawerWallpaperStore(context).current.value).isNull()
    }
}
