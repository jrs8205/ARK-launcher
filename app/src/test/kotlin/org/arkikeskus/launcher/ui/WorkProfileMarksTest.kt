package org.arkikeskus.launcher.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.arkikeskus.launcher.data.WorkProfile
import org.arkikeskus.launcher.ui.component.LocalProfileMarks
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launcher window's root names the work profile for every icon under it: its apps say "Mail, Work" to a
 * screen reader for as long as the profile exists, paused or not, and nothing is marked without one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WorkProfileMarksTest {
    @get:Rule val compose = createComposeRule()

    private val profile = MutableStateFlow<WorkProfile?>(null)
    private var marks: Map<Long, String>? = null

    private fun compose() {
        compose.setContent { WorkProfileMarks(profile) { marks = LocalProfileMarks.current } }
        compose.waitForIdle()
    }

    @Test fun theWorkProfilesAppsAreMarkedWithItsTabsName() {
        profile.value = WorkProfile(userSerial = 12, paused = false)
        compose()

        assertThat(marks).containsExactly(12L, "Work")
    }

    @Test
    @Config(qualifiers = "fi")
    fun theNameIsTheLanguagesOwn() {
        profile.value = WorkProfile(userSerial = 12, paused = false)
        compose()

        assertThat(marks).containsExactly(12L, "Työ")
    }

    @Test fun aPausedProfilesAppsAreMarkedToo() {
        profile.value = WorkProfile(userSerial = 12, paused = true)
        compose()

        assertThat(marks).containsExactly(12L, "Work")
    }

    @Test fun withoutAWorkProfileNothingIsMarked() {
        compose()

        assertThat(marks).isEmpty()
    }

    @Test fun aProfileThatComesAndGoesIsFollowed() {
        compose()

        profile.value = WorkProfile(userSerial = 14, paused = false)
        compose.waitForIdle()
        assertThat(marks).containsExactly(14L, "Work")

        profile.value = null
        compose.waitForIdle()
        assertThat(marks).isEmpty()
    }
}
