package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.smartspace.CalendarEvent
import org.arkikeskus.launcher.data.smartspace.CurrentWeather
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap on the smartspace weather opens the app the user chose in Settings (0.9.6, Telegram ask):
 * on the short widget's single detail line, on the compact widget's merged middle row and on the
 * spacious widget's own weather row. Without a chosen (and installed) app the line stays plain text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SmartspaceWeatherTapTest {
    @get:Rule val compose = createComposeRule()

    private val weatherApp = AppItem(
        packageName = "com.weather", className = "com.weather.Main", user = Process.myUserHandle(),
        userSerial = 0, label = "Weather",
    )
    private val weather = CurrentWeather(temperatureC = 12.4, weatherCode = 0, city = "Oulu", fetchedAt = 0L)
    private val event = CalendarEvent(eventId = 1, title = "Dentist", begin = Long.MAX_VALUE / 2, end = Long.MAX_VALUE / 2 + 1, allDay = false)
    private var opened = 0

    private fun state(app: AppItem?, nextEvent: CalendarEvent? = null) = SmartspaceState(
        hasCalendarPermission = true,
        nextEvent = nextEvent,
        hasLocationPermission = true,
        showWeather = true,
        weather = weather,
        weatherApp = app,
    )

    /** The widget inside a footprint [height] dp tall: < 140 is the short layout, 140..239 compact, 240+ spacious. */
    private fun show(state: SmartspaceState, height: Int) {
        compose.setContent {
            Box(Modifier.size(360.dp, height.dp)) {
                SmartspaceContent(state, onRefresh = {}, onOpenWeatherApp = { opened++ }, modifier = Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
    }

    private fun weatherLine() = compose.onNode(hasText("12°", substring = true))

    @Test fun theSpaciousWeatherRowOpensTheChosenApp() {
        show(state(weatherApp), height = 280)
        weatherLine().assertHasClickAction().performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun theCompactMergedRowsWeatherOpensTheChosenApp() {
        show(state(weatherApp, nextEvent = event), height = 200)
        weatherLine().assertHasClickAction().performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun theShortWidgetsWeatherLineOpensTheChosenApp() {
        show(state(weatherApp), height = 120)
        weatherLine().assertHasClickAction().performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun withoutAChosenAppTheWeatherRowStaysPlainText() {
        show(state(app = null), height = 280)
        weatherLine().assertHasNoClickAction()
    }

    @Test fun withoutAChosenAppTheShortWidgetsWeatherLineStaysPlainText() {
        show(state(app = null), height = 120)
        weatherLine().assertHasNoClickAction()
    }

    @Test fun theChosenAppResolvesOnlyWhileItIsInstalled() {
        val apps = listOf(weatherApp)
        assertThat(weatherAppIn(apps, "")).isNull()
        assertThat(weatherAppIn(apps, "com.gone/Main/0")).isNull()
        assertThat(weatherAppIn(apps, weatherApp.key)).isEqualTo(weatherApp)
    }
}
