package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Count apps opened elsewhere" (issue #33) reads Android's usage access, which only the user can give, in
 * Android's own settings. Turned on without it, the switch takes the user there; until the access is given the
 * row says that nothing is counted from elsewhere yet, so a switch that is on is never taken for one that works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class CountSystemUsageToggleTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val plain = res.getString(R.string.settings_count_system_usage_desc)
    private val needsAccess = res.getString(R.string.settings_count_system_usage_needs_access)

    private var on by mutableStateOf(false)
    private var access = false
    private var opened = 0

    private fun show() {
        compose.setContent {
            CountSystemUsageToggle(on = on, onSet = { on = it }, hasAccess = { access }, openAccess = { opened++ })
        }
        compose.waitForIdle()
    }

    @Test fun turnedOnWithoutTheAccessItOpensAndroidsPageAndSaysNothingIsCountedYet() {
        show()
        compose.onNodeWithText(plain).assertExists()

        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()

        assertThat(on).isTrue()
        assertThat(opened).isEqualTo(1)
        compose.onNodeWithText(needsAccess).assertExists()
    }

    @Test fun turnedOnWithTheAccessItSimplyTurnsOn() {
        access = true
        show()

        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()

        assertThat(on).isTrue()
        assertThat(opened).isEqualTo(0)
        compose.onNodeWithText(plain).assertExists()
        compose.onNodeWithText(needsAccess).assertDoesNotExist()
    }

    @Test fun turnedOffItOpensNothing() {
        on = true
        access = true
        show()

        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()

        assertThat(on).isFalse()
        assertThat(opened).isEqualTo(0)
    }
}
