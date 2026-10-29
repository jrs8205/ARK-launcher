package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.smartspace.CalendarEvent
import org.arkikeskus.launcher.data.smartspace.CurrentWeather
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Date

/**
 * WCAG 2.5.5 target size for the smartspace's tappable lines (owner ask 2026-10-29): the clock, the
 * weather, the date, the alarm, the event and the permission prompts are small text, so each gets a
 * hit area at least 44 dp tall and as wide as the card whenever the footprint has the room; a short
 * footprint keeps the lines at their natural height instead of overflowing the widget.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartspaceTouchTargetTest {
    @get:Rule val compose = createComposeRule()
    private val context: Application = RuntimeEnvironment.getApplication()

    private val weatherApp = AppItem(
        packageName = "com.weather", className = "com.weather.Main", user = Process.myUserHandle(),
        userSerial = 0, label = "Weather",
    )
    private val weather = CurrentWeather(temperatureC = 12.4, weatherCode = 0, city = "Oulu", fetchedAt = 0L)
    private val event = CalendarEvent(eventId = 1, title = "Dentist", begin = Long.MAX_VALUE / 2, end = Long.MAX_VALUE / 2 + 1, allDay = false)
    private val state = SmartspaceState(
        hasCalendarPermission = true, nextEvent = event, hasLocationPermission = true,
        showWeather = true, weather = weather, weatherApp = weatherApp,
    )

    private fun show(height: Int) {
        compose.setContent {
            Box(Modifier.size(360.dp, height.dp)) {
                SmartspaceContent(state, onRefresh = {}, onOpenWeatherApp = {}, modifier = Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
    }

    private val density get() = Density(context)
    private fun SemanticsNode.heightDp() = with(density) { boundsInRoot.height.toDp() }
    private fun SemanticsNode.widthDp() = with(density) { boundsInRoot.width.toDp() }
    private fun tappables() = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
    private fun clockText() = android.text.format.DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis()))
    private val isClock = SemanticsMatcher("is the clock") { hasText(clockText()).matches(it) }

    @Test fun theSpaciousLayoutsTappableLinesAreAtLeast44dpTall() {
        show(height = 280)
        val nodes = tappables()
        // The clock, the weather, the date and the event.
        assertThat(nodes).hasSize(4)
        nodes.forEach { assertThat(it.heightDp().value).isAtLeast(44f) }
    }

    @Test fun theCompactMergedRowsItemsAreAtLeast44dpTall() {
        show(height = 200)
        val nodes = tappables()
        assertThat(nodes).hasSize(4)
        nodes.forEach { assertThat(it.heightDp().value).isAtLeast(44f) }
    }

    @Test fun theShortLayoutsLinesAreAtLeast44dpTallWhenItHasRoom() {
        show(height = 120)
        val nodes = tappables()
        // The clock, the date and the detail line (the event).
        assertThat(nodes).hasSize(3)
        nodes.forEach { assertThat(it.heightDp().value).isAtLeast(44f) }
    }

    @Test fun aTightFootprintKeepsTheLinesAtTheirNaturalHeight() {
        show(height = 64)
        val lines = tappables().filterNot { isClock.matches(it) }
        assertThat(lines).hasSize(2)
        lines.forEach { assertThat(it.heightDp().value).isLessThan(30f) }
    }

    @Test fun theTappableLinesSpanTheCardsWidth() {
        show(height = 280)
        val nodes = tappables()
        val clock = nodes.single { isClock.matches(it) }
        val lines = nodes.filterNot { isClock.matches(it) }
        assertThat(lines).hasSize(3)
        // Every line is as wide as the card's content, so it is at least as wide as the big clock.
        lines.forEach { assertThat(it.widthDp().value).isAtLeast(clock.widthDp().value) }
        assertThat(lines.map { it.widthDp().value }.toSet()).hasSize(1)
    }
}
