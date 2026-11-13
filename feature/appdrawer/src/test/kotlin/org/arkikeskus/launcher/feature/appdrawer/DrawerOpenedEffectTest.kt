package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
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

/**
 * When the drawer reads again what has no broadcast of its own (Android's "hide private space when locked"):
 * when it opens, and every time the launcher comes back to the front while it is open. The second matters: the
 * drawer stays open behind Android's settings, so a user who hides the space there and comes Back has opened
 * nothing, and the tab of a now hidden space would stay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerOpenedEffectTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()
    private var open by mutableStateOf(true)
    private var told = 0

    private fun show() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { DrawerOpenedEffect(open) { told++ } }
        }
        compose.waitForIdle()
    }

    private fun leaveAndComeBack() {
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.waitForIdle()
    }

    @Test fun anOpenDrawerIsToldOnceWhenItOpens() {
        show()
        assertThat(told).isEqualTo(1)
    }

    @Test fun comingBackToAnOpenDrawerTellsItAgain() {
        show()

        leaveAndComeBack()
        assertThat(told).isEqualTo(2)
        leaveAndComeBack()
        assertThat(told).isEqualTo(3)
    }

    @Test fun aClosedDrawerIsNotToldAndIsToldWhenItOpens() {
        open = false
        show()
        leaveAndComeBack()
        assertThat(told).isEqualTo(0)

        open = true
        compose.waitForIdle()
        assertThat(told).isEqualTo(1)
    }
}
