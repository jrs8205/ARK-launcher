package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import org.arkikeskus.launcher.data.smartspace.CalendarRepository
import org.arkikeskus.launcher.data.smartspace.WeatherRepository
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date
import java.util.TimeZone

/**
 * The clocks on the home screen follow the phone's: the status bar's turns with the minute and is
 * current the moment the screen comes back (it used to poll every 15 s on a timer that slept with the
 * phone), and both it and the smartspace re-format the moment the time zone changes (the smartspace
 * kept one formatter per hour, so a landing showed the old zone for up to an hour).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rGB")
class ClockTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val defaultZone: TimeZone = TimeZone.getDefault()

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @After fun restoreZone() = TimeZone.setDefault(defaultZone)

    /** What the phone's own formatter says right now — zone and 12/24 h read afresh. */
    private fun phoneSays(at: Long): String = android.text.format.DateFormat.getTimeFormat(context).format(Date(at))

    @Test fun theBarClockTurnsWithTheMinuteAndIsCurrentOnResume() {
        var now = 1_800_000_000_000L
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Text(rememberClock { now }, Modifier.testTag("clock"))
            }
        }
        compose.onNodeWithTag("clock").assertTextEquals(phoneSays(now))

        // The minute turns: the clock waits for the system's tick, then shows it.
        val before = now
        now += 60_000
        compose.waitForIdle()
        compose.onNodeWithTag("clock").assertTextEquals(phoneSays(before))
        context.sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
        compose.waitForIdle()
        compose.onNodeWithTag("clock").assertTextEquals(phoneSays(now))

        // The phone slept an hour with the screen off: the clock is right as soon as home is back.
        now += 3_600_000
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("clock").assertTextEquals(phoneSays(now))
    }

    @Test fun aTimeZoneChangeReformatsAtOnce() {
        val at = 1_800_000_000_000L
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Helsinki"))
        compose.setContent { Text(rememberTimeFormat().format(Date(at)), Modifier.testTag("time")) }
        val helsinki = phoneSays(at)
        compose.onNodeWithTag("time").assertTextEquals(helsinki)

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        compose.waitForIdle()

        val london = phoneSays(at)
        assertThat(london).isNotEqualTo(helsinki)
        compose.onNodeWithTag("time").assertTextEquals(london)
    }

    /** The big smartspace clock, which kept one formatter per UTC hour, follows the zone at once. */
    @Test fun theSmartspaceClockFollowsATimeZoneChange() {
        val testScope = TestScope()
        val wiring = ProductionWiring(context, testScope)
        val viewModel = SmartspaceViewModel(
            CalendarRepository(context, testScope.backgroundScope),
            WeatherRepository(context, testScope.backgroundScope),
            wiring.settingsRepository,
        )
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Helsinki"))
        val at = System.currentTimeMillis()
        compose.setContent { SmartspaceWidget(viewModel = viewModel) }
        val helsinki = phoneSays(at)
        compose.onNodeWithText(helsinki).assertExists()

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        compose.waitForIdle()

        val london = phoneSays(at)
        assertThat(london).isNotEqualTo(helsinki)
        compose.onNodeWithText(london).assertExists()
        wiring.close()
    }
}
