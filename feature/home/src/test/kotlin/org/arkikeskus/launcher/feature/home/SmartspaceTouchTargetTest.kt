package org.arkikeskus.launcher.feature.home

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
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

    private fun show(height: Int, fontScale: Float = 1f, shown: SmartspaceState = state) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Box(Modifier.size(360.dp, height.dp)) {
                    SmartspaceContent(shown, onRefresh = {}, onOpenWeatherApp = {}, modifier = Modifier.fillMaxSize())
                }
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

    /** A bigger system font makes the clock taller than the budget's estimate used to assume; the
     *  lines must still share the room evenly instead of the last one being squeezed off the card. */
    @Test fun aLargeSystemFontKeepsEveryLineWithinTheFootprint() {
        // Four lines under the clock: weather, date, the event and the location prompt.
        show(height = 240, fontScale = 2f, shown = state.copy(hasLocationPermission = false))
        val lines = tappables().filterNot { isClock.matches(it) }
        assertThat(lines).hasSize(4)
        val heights = lines.map { it.heightDp().value }
        assertThat(heights.max() - heights.min()).isLessThan(1f)
        lines.forEach { assertThat(it.boundsInRoot.bottom).isAtMost(with(density) { 240.dp.toPx() } + 0.5f) }
    }

    /** The budget grew the earlier lines to 44 dp and the Column then squeezed what came after them:
     *  a two-line event lost its second line. Every line keeps its natural height; only the room
     *  left after that grows the touch areas. */
    @Test fun aTwoLineEventKeepsBothLinesWhenTheEarlierLinesGrow() {
        seedAlarm()
        val long = event.copy(title = "Dentist appointment for the whole family at the health centre")
        show(height = 260, fontScale = 1.3f, shown = state.copy(nextEvent = long))
        val lines = tappables().filterNot { isClock.matches(it) }
        // The weather, the date, the alarm and the event.
        assertThat(lines).hasSize(4)
        assertThat(compose.onAllNodes(hasContentDescription(context.getString(R.string.smartspace_next_alarm)), useUnmergedTree = true)
            .fetchSemanticsNodes()).isNotEmpty()
        assertThat(textLayout(long.title).lineCount).isEqualTo(2)
        lines.forEach { assertThat(it.boundsInRoot.bottom).isAtMost(with(density) { 260.dp.toPx() } + 0.5f) }
    }

    /** The same with the location prompt as the last line: it is never pushed off the card. */
    @Test fun theLastLineIsNeverShorterThanItsText() {
        val long = event.copy(title = "Dentist appointment for the whole family at the health centre")
        show(height = 260, fontScale = 1.3f, shown = state.copy(nextEvent = long, hasLocationPermission = false))
        val lines = tappables().filterNot { isClock.matches(it) }
        assertThat(lines).hasSize(4)
        val prompt = context.getString(R.string.smartspace_allow_location)
        val promptText = compose.onNode(hasText(prompt), useUnmergedTree = true).fetchSemanticsNode()
        val promptLine = lines.single { it.boundsInRoot.contains(promptText.boundsInRoot.center) }
        assertThat(promptLine.heightDp().value).isAtLeast(promptText.heightDp().value - 0.5f)
        assertThat(textLayout(long.title).lineCount).isEqualTo(2)
        lines.forEach { assertThat(it.boundsInRoot.bottom).isAtMost(with(density) { 260.dp.toPx() } + 0.5f) }
    }

    private fun seedAlarm() {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getBroadcast(context, 0, Intent("alarm"), PendingIntent.FLAG_IMMUTABLE)
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 3_600_000L, show), show)
    }

    private fun textLayout(text: String): TextLayoutResult {
        val node = compose.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
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
