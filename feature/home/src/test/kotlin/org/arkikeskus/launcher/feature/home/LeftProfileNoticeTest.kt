package org.arkikeskus.launcher.feature.home

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
import kotlinx.coroutines.flow.MutableStateFlow
import org.arkikeskus.launcher.ui.HomeNotice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review round 15. That a home profile was left for another one is owed to the user until a screen has shown
 * it. The view model used to call it told the moment it had queued the notice for a screen: with no screen
 * there before the view model went (the home activity was destroyed), the queue went with it and nobody was
 * told. The screen is what says the notice was shown, and it says so after showing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LeftProfileNoticeTest {
    @get:Rule val compose = createComposeRule()

    /** The home activity's lifecycle, moved by the test. */
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()
    private val left = MutableStateFlow<Long?>(null)
    private val steps = mutableListOf<String>()

    /** Whether the home screen is what the user sees, as far as the launcher's own layers go (no app drawer
     *  over it). */
    private var onScreen by mutableStateOf(true)

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LeftProfileNotice(
                    left = left,
                    onScreen = onScreen,
                    show = { message -> steps += if (message == HomeNotice.ProfileNotRead.message) "the text is on screen" else "another text" },
                    shown = { profileId ->
                        steps += "profile $profileId was told of"
                        left.value = null
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun theNoticeIsShownAndOnlyThenSaidToHaveBeenShown() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        show()
        // Nothing is owed: nothing is shown.
        assertThat(steps).isEmpty()

        left.value = 2L
        compose.waitForIdle()

        assertThat(steps).containsExactly("the text is on screen", "profile 2 was told of").inOrder()
    }

    /**
     * Review round 16. The home screen is part of the launcher's window also while the app drawer covers it,
     * and stays so while the home activity is behind Settings. A profile left then was "shown": a toast over
     * Settings or the drawer, called told at once, and back on the home screen nothing said why it was
     * another one. The notice waits until the home screen is what the user sees: the home activity resumed,
     * and no drawer over the home screen.
     */
    @Test fun theNoticeWaitsUntilTheHomeScreenIsWhatTheUserSees() {
        // The home activity is behind Settings: started, not resumed.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        show()
        left.value = 2L
        compose.waitForIdle()
        assertThat(steps).isEmpty()
        assertThat(left.value).isEqualTo(2L)

        // Back in the home activity, with the app drawer over the home screen.
        onScreen = false
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.waitForIdle()
        assertThat(steps).isEmpty()
        assertThat(left.value).isEqualTo(2L)

        // The drawer closes.
        onScreen = true
        compose.waitForIdle()
        assertThat(steps).containsExactly("the text is on screen", "profile 2 was told of").inOrder()
    }

    /** And a home activity that leaves the front again before anything was owed owes nothing shown later
     *  either, until it is back. */
    @Test fun aNoticeThatComesWhileTheHomeActivityIsAwayWaitsForItsReturn() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        show()
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }

        left.value = 3L
        compose.waitForIdle()
        assertThat(steps).isEmpty()

        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.waitForIdle()
        assertThat(steps).containsExactly("the text is on screen", "profile 3 was told of").inOrder()
    }
}
