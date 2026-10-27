package org.arkikeskus.launcher.data.smartspace

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * The all-day query starts a day early (so an all-day event west of UTC does not vanish in the
 * evening), which also brings in yesterday's all-day instances. Those have ended and must not
 * count against the load's size limit: a day full of them hid today's event entirely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CalendarLoadTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val defaultZone: TimeZone = TimeZone.getDefault()

    @After fun tearDown() {
        scope.cancel()
        TimeZone.setDefault(defaultZone)
    }

    /** The calendar provider: all-day instances as UTC midnights, in BEGIN order, as the real one. */
    class FakeCalendar : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
            val cursor = MatrixCursor(projection)
            if (selection?.contains("${CalendarContract.Instances.ALL_DAY} = 1") == true) {
                rows.forEachIndexed { i, (title, begin, end) -> cursor.addRow(arrayOf<Any>(i.toLong(), title, begin, end, 1)) }
            }
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

        companion object {
            var rows: List<Triple<String, Long, Long>> = emptyList()
        }
    }

    @Test fun aDayFullOfEndedAllDayEventsDoesNotHideTodays() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Helsinki"))
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR)
        val day = 24 * 60 * 60 * 1000L
        // Today's LOCAL date as the provider stores an all-day instance: its UTC midnight. (The
        // UTC date is yesterday's between Helsinki midnight and the UTC day change.)
        val todayUtc = LocalDate.now(ZoneId.of("Europe/Helsinki")).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        FakeCalendar.rows = List(50) { Triple("Yesterday $it", todayUtc - day, todayUtc) } + Triple("Today", todayUtc, todayUtc + day)
        Robolectric.buildContentProvider(FakeCalendar::class.java).create(ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
        val repository = CalendarRepository(context, scope)

        repository.refresh()
        val deadline = System.currentTimeMillis() + 5_000
        while (repository.events.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertThat(repository.events.value.map { it.title }).contains("Today")
    }
}
