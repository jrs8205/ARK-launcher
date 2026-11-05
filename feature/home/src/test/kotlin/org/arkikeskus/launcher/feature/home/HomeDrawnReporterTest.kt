package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The launcher tells the system when its home is really on screen (reportFullyDrawn): the rows loaded
 * and laid out, not the first blank frame. The startup benchmarks read it as the time to full display,
 * and the system's own startup accounting stops there too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomeDrawnReporterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeReportsItselfDrawnOnceItsRowsHaveLoaded() {
        var loaded by mutableStateOf(false)
        compose.setContent { HomeDrawnReporter(loaded = loaded) }
        compose.waitForIdle()
        assertThat(compose.activity.fullyDrawnReporter.isFullyDrawnReported).isFalse()

        compose.runOnIdle { loaded = true }
        compose.waitForIdle()
        // The activity reports after its next draw, which Robolectric does not run on its own: the
        // decor view's draw listeners are fired by hand, then the posted report runs on the main looper.
        compose.runOnIdle { compose.activity.window.decorView.viewTreeObserver.dispatchOnDraw() }
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(compose.activity.fullyDrawnReporter.isFullyDrawnReported).isTrue()
    }
}
