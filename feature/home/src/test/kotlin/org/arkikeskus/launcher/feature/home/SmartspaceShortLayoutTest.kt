package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.os.Process
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.data.smartspace.CalendarEvent
import org.arkikeskus.launcher.data.smartspace.CurrentWeather
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rGB-w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartspaceShortLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context: Application = RuntimeEnvironment.getApplication()
    private val now = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 16, 12, 14)
    }.timeInMillis
    private val time = DateFormat.getTimeFormat(context).format(Date(now))
    private val date = DateUtils.formatDateTime(context, now, DateUtils.FORMAT_SHOW_WEEKDAY or
        DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
    private val event = CalendarEvent(eventId = 1, title = "Dentist", begin = now, end = now + 60_000, allDay = true)
    private var state by mutableStateOf(SmartspaceState(true, event, true, true, null, null))
    private var height by mutableIntStateOf(120)
    private var style by mutableStateOf(SmartspaceStyle())

    private fun show(width: Int = 360, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalSmartspaceStyle provides style,
                LocalWeatherInFahrenheit provides false,
            ) {
                Box(Modifier.size(width.dp, height.dp).testTag("footprint")) {
                    SmartspaceContent(state, {}, {}, Modifier.fillMaxSize(), clock = { now })
                }
            }
        }
    }

    private val texts = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    /** Read the unmerged tree, including the actual text layout and its unclipped coordinates. */
    private fun assertStack(vararg expected: String) {
        val footprint = compose.onNodeWithTag("footprint").fetchSemanticsNode().boundsInRoot
        val nodes = compose.onAllNodes(texts, useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.positionInRoot.y }
        val labels = nodes.map { it.config[SemanticsProperties.Text].single().text }
        assertThat(labels).containsExactlyElementsIn(expected.toList()).inOrder()
        assertThat(compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()).hasSize(expected.size)
        var bottom = footprint.top
        for (node in nodes) {
            val pos = node.positionInRoot
            val size = node.size
            assertThat(pos.x + size.width / 2f).isWithin(1f).of(footprint.center.x)
            assertThat(pos.x).isAtLeast(footprint.left)
            assertThat(pos.x + size.width).isAtMost(footprint.right)
            assertThat(pos.y).isAtLeast(bottom)
            bottom = pos.y + size.height
            assertThat(bottom).isAtMost(footprint.bottom)
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            assertThat(layout.lineCount).isEqualTo(1)
            assertThat(layout.multiParagraph.height).isAtMost(size.height.toFloat())
        }
    }

    private fun footprintPixels(): IntArray {
        val bitmap = compose.onNodeWithTag("footprint").captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also { pixels ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    @Test fun theClockDateAndEventAreCentredInAStack() {
        show()
        assertStack(time, date, event.title)
    }

    @Test fun aLongEventIsEllipsizedInTheNarrowWidget() {
        val title = "Dentist appointment for the whole family at the health centre"
        state = state.copy(nextEvent = event.copy(title = title))
        show(width = 180)
        assertStack(time, date, title)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(title), useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        assertThat(results.single().isLineEllipsized(0)).isTrue()
        val fullWidth = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().first().size.width
        height = 64
        assertStack(time, date)
        assertThat(compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().first().size.width).isLessThan(fullWidth)
    }

    @Test fun resizingKeepsOnlyTheWholeLinesThatFit() {
        show()
        for (h in listOf(139, 120, 111, 96, 84, 76, 75, 74, 67, 64, 60, 59, 50, 111)) {
            height = h
            when (h) {
                in 0..59 -> assertStack(time)
                in 60..75 -> assertStack(time, date)
                else -> assertStack(time, date, event.title)
            }
        }
    }

    @Test fun hiddenWeatherAndCalendarPromptLoseTheirTextAndAction() {
        val app = AppItem("com.weather", "Main", Process.myUserHandle(), 0, "Weather")
        state = state.copy(nextEvent = null, weatherApp = app,
            weather = CurrentWeather(12.0, 0, city = "Oulu", fetchedAt = 0L))
        style = SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_COLOR,
            weatherIconSize = LauncherSettings.WEATHER_SIZE_XLARGE)
        show()
        compose.onNodeWithTag(WEATHER_GLYPH_TAG, useUnmergedTree = true).assertExists()
        height = 84 // Enough for an ordinary detail line, but not this large icon.
        assertStack(time, date)
        compose.onNodeWithTag(WEATHER_GLYPH_TAG, useUnmergedTree = true).assertDoesNotExist()
        height = 120
        state = state.copy(hasCalendarPermission = false)
        assertStack(time, date, context.getString(R.string.smartspace_allow_calendar))
        height = 64
        assertStack(time, date)
    }

    @Test fun noEventOrWeatherLeavesJustTheDateUnderTheClock() {
        state = state.copy(nextEvent = null)
        show()
        assertStack(time, date)
        height = 50
        assertStack(time)
    }

    @Test fun systemFontScaleTwoKeepsWholeLines() {
        show(fontScale = 2f)
        assertStack(time, date)
    }

    @Test fun theLargestWidgetTextKeepsWholeLines() {
        height = 111
        style = SmartspaceStyle(textScale = 1.4f)
        show()
        assertStack(time, date, event.title)
        height = 84
        assertStack(time, date)
    }

    @Test fun evenTheClockDisappearsWhenItCannotFitAndReturnsOnResize() {
        height = 50
        show()
        val clockHeight = compose.onNodeWithTag(SMARTSPACE_CLOCK_TAG, useUnmergedTree = true).fetchSemanticsNode().size.height
        height = clockHeight // mdpi: exactly the unpadded clock
        assertStack(time)
        height = clockHeight - 1
        assertStack()
        val empty = footprintPixels()
        assertThat(empty.all { it == empty.first() }).isTrue()
        height = 10
        assertStack()
        height = 50
        assertStack(time)
    }

    @Test fun theTallerLayoutsKeepTheirClockWeatherDateAndPrompt() {
        state = state.copy(hasCalendarPermission = false, nextEvent = null,
            weather = CurrentWeather(12.0, 0, city = "Oulu", fetchedAt = 0L))
        height = 140
        show()
        for (h in listOf(140, 200, 280)) {
            height = h
            val clock = compose.onNodeWithTag(SMARTSPACE_CLOCK_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val weather = compose.onNode(hasText("12°", substring = true), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val day = compose.onNode(hasText(date), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertThat(weather.top).isAtLeast(clock.bottom)
            if (h < 240) assertThat(weather.center.y).isWithin(1f).of(day.center.y)
            else assertThat(day.top).isAtLeast(weather.bottom)
        }
        height = 139
        assertStack(time, date, context.getString(R.string.smartspace_allow_calendar))
    }

    @Test fun eachVisibleRowKeepsItsTouchBoundsOffTheOtherRowsAndTheirText() {
        show()
        for (h in listOf(76, 80, 84, 96, 111, 139, 64)) {
            height = h
            val labels = if (h == 64) listOf(time, date) else listOf(time, date, event.title)
            val rows = labels.associateWith { label ->
                compose.onNode(hasClickAction() and hasText(label)).fetchSemanticsNode()
            }
            for ((label, row) in rows) {
                val textBounds = compose.onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertThat(row.boundsInRoot.intersect(textBounds)).isEqualTo(textBounds)
                for ((otherLabel, other) in rows) {
                    if (label == otherLabel) continue
                    val touch = other.touchBoundsInRoot
                    assertWithMessage("${h}dp: '$otherLabel' touch $touch over '$label' text $textBounds")
                        .that(touch.overlaps(textBounds)).isFalse()
                    assertWithMessage("${h}dp: '$otherLabel' touch $touch over '$label' row ${row.boundsInRoot}")
                        .that(touch.overlaps(row.boundsInRoot)).isFalse()
                }
                assertWithMessage("${h}dp: '$label' touch bounds").that(row.touchBoundsInRoot).isEqualTo(row.boundsInRoot)
            }
        }
    }

    @Test fun touchingEachTightRowOpensItsOwnDestination() {
        height = 76
        show()
        val footprint = compose.onNodeWithTag("footprint")
        fun tap(label: String): Intent {
            val text = compose.onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode()
            val point = text.boundsInRoot.center - footprint.fetchSemanticsNode().boundsInRoot.topLeft
            footprint.performTouchInput { click(point) }
            compose.waitForIdle()
            return checkNotNull(shadowOf(context).nextStartedActivity) { "Tap on '$label' did not open an activity" }
        }
        val dayUri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(now.toString()).build()
        assertThat(tap(time).action).isEqualTo(AlarmClock.ACTION_SHOW_ALARMS)
        assertThat(tap(date).data).isEqualTo(dayUri)
        assertThat(tap(event.title).data).isEqualTo(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
    }
}
