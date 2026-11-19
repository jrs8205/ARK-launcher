package org.arkikeskus.launcher.feature.home

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.smartspace.CalendarEvent
import org.arkikeskus.launcher.data.smartspace.CurrentWeather
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar
import java.util.Date

/**
 * The compact layout's middle row (the date and the next alarm, with the weather when it is on) on a widget too
 * narrow for all of it. Issue #29: the date took its full width first and the alarm, measured with what was
 * left, wrapped its time onto a second line that the row does not show ("Mo., 16. November · alarm Di").
 * The alarm is whole whatever the width; the date gives way, first to its short form, then to an ellipsis.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartspaceMergedRowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Application = RuntimeEnvironment.getApplication()

    /** Monday 16 November 2026 at 12:14: a long month name, as in the report. */
    private val now = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 16, 12, 14)
    }.timeInMillis
    private val dateFlags = DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR
    private val fullDate = DateUtils.formatDateTime(context, now, dateFlags)
    private val shortDate = DateUtils.formatDateTime(context, now, dateFlags or DateUtils.FORMAT_ABBREV_MONTH)

    /** The day after tomorrow at 6:30 by the test's own clock: never "today", so the alarm's text carries its
     *  weekday, and always the same one ("Wed"). Counted from the day the test runs on, the weekday changed
     *  with the calendar, and with a narrow one ("Fri") the full date fitted where the test needs it not to. */
    private val alarmAt = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 2)
        set(Calendar.HOUR_OF_DAY, 6)
        set(Calendar.MINUTE, 30)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val alarmTime = android.text.format.DateFormat.getTimeFormat(context).format(Date(alarmAt))
    private val alarmText = DateUtils.formatDateTime(
        context, alarmAt, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
    ) + " " + alarmTime

    private val event = CalendarEvent(eventId = 1, title = "Open Play", begin = now + 5 * 3_600_000L, end = now + 6 * 3_600_000L, allDay = false)
    private val state = SmartspaceState(
        hasCalendarPermission = true, nextEvent = event, hasLocationPermission = true,
        showWeather = false, weather = null, weatherApp = null,
    )
    private val weather = CurrentWeather(temperatureC = 1.0, weatherCode = 3, city = "Oulu", fetchedAt = 0L)

    private fun show(width: Int, shown: SmartspaceState = state) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 0, Intent("alarm"), PendingIntent.FLAG_IMMUTABLE)
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(alarmAt, intent), intent)
        compose.setContent {
            Box(Modifier.size(width.dp, 200.dp)) {
                SmartspaceContent(shown, onRefresh = {}, onOpenWeatherApp = {}, modifier = Modifier.fillMaxSize(), clock = { now })
            }
        }
        compose.waitForIdle()
    }

    private fun textNode(text: String): SemanticsNode =
        compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /**
     * The text is drawn whole: on one line, with no ellipsis, nothing wrapped out of sight and nothing clipped at
     * its end. (`hasVisualOverflow` cannot say this: for a text that does not wrap, or one with an ellipsis, it
     * compares the text's size with the width the paragraph was offered.)
     */
    private fun assertWhole(text: String) {
        val layout = layoutOf(textNode(text))
        assertThat(layout.lineCount).isEqualTo(1)
        assertThat(layout.isLineEllipsized(0)).isFalse()
        assertThat(layout.getLineEnd(0, visibleEnd = true)).isEqualTo(text.length)
        assertThat(layout.size.width.toFloat()).isAtLeast(layout.multiParagraph.maxIntrinsicWidth - 0.5f)
    }

    private fun widthPx(dp: Int) = with(Density(context)) { dp.dp.toPx() }

    @Test fun theTestsDatesDiffer() {
        assertThat(shortDate).isNotEqualTo(fullDate)
        assertThat(shortDate.length).isLessThan(fullDate.length)
    }

    @Test fun aWideWidgetShowsTheFullDateAndTheAlarm() {
        show(width = 360)
        compose.onNodeWithText(fullDate, useUnmergedTree = true).assertExists()
        assertWhole(alarmText)
    }

    @Test fun aWidgetTooNarrowForTheFullDateShowsItsShortForm() {
        show(width = NARROW)
        compose.onNodeWithText(shortDate, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(fullDate, useUnmergedTree = true).assertDoesNotExist()
        assertWhole(shortDate)
    }

    @Test fun theAlarmKeepsItsTimeBesideADateThatDoesNotFit() {
        show(width = NARROW)
        assertWhole(alarmText)
        assertThat(textNode(alarmText).boundsInRoot.right).isAtMost(widthPx(NARROW) + 0.5f)
    }

    /** Too narrow even for the short date: the date is cut, the alarm is not. */
    @Test fun onAVeryNarrowWidgetTheDateIsCutNotTheAlarm() {
        show(width = VERY_NARROW)
        assertWhole(alarmText)
        assertThat(textNode(alarmText).boundsInRoot.right).isAtMost(widthPx(VERY_NARROW) + 0.5f)
        assertThat(layoutOf(textNode(shortDate)).isLineEllipsized(0)).isTrue()
    }

    /** With the weather in the row too, the place gives way before the date, and the alarm is still whole. */
    @Test fun withTheWeatherInTheRowTheAlarmIsStillWhole() {
        show(width = NARROW, shown = state.copy(showWeather = true, weather = weather))
        assertWhole(alarmText)
        assertThat(textNode(alarmText).boundsInRoot.right).isAtMost(widthPx(NARROW) + 0.5f)
        compose.onNodeWithText(shortDate, useUnmergedTree = true).assertExists()
    }

    private companion object {
        /** dp: the full date and the alarm do not fit side by side, the short date and the alarm do. */
        const val NARROW = 240
        /** dp: not even the short date fits beside the alarm. */
        const val VERY_NARROW = 190
    }
}
