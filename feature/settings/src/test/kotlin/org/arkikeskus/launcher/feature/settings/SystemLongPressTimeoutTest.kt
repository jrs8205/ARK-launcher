package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The system long-press timeout shown by the delay row follows the accessibility setting on resume. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SystemLongPressTimeoutTest {
    @get:Rule val compose = createComposeRule()

    private val system = object : ViewConfiguration {
        override var longPressTimeoutMillis = 400L
        override val doubleTapTimeoutMillis = 300L
        override val doubleTapMinTimeMillis = 40L
        override val touchSlop = 24f
    }
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun theTimeoutIsReadAgainWhenTheScreenResumes() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        var seen = 0L
        compose.setContent {
            CompositionLocalProvider(LocalViewConfiguration provides system, LocalLifecycleOwner provides owner) {
                seen = rememberSystemLongPressTimeout()
            }
        }
        compose.waitForIdle()
        assertThat(seen).isEqualTo(400L)

        // The user changes Android's "Touch & hold delay" while Settings sits in the background.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        system.longPressTimeoutMillis = 1000L
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertThat(seen).isEqualTo(1000L)
    }
}
