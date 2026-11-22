package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
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

    @Test fun theNoticeIsShownAndOnlyThenSaidToHaveBeenShown() {
        val left = MutableStateFlow<Long?>(null)
        val steps = mutableListOf<String>()
        compose.setContent {
            LeftProfileNotice(
                left = left,
                show = { message -> steps += if (message == HomeNotice.ProfileNotRead.message) "the text is on screen" else "another text" },
                shown = { profileId ->
                    steps += "profile $profileId was told of"
                    left.value = null
                },
            )
        }
        compose.waitForIdle()
        // Nothing is owed: nothing is shown.
        assertThat(steps).isEmpty()

        left.value = 2L
        compose.waitForIdle()

        assertThat(steps).containsExactly("the text is on screen", "profile 2 was told of").inOrder()
    }
}
