package org.arkikeskus.launcher.data.backup

import android.Manifest
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.FakeAppRowResolver
import org.arkikeskus.launcher.data.InMemoryDataStore
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.ScreensaverSettingsStore
import org.arkikeskus.launcher.data.screensaver.screensaverPreferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The screensaver's settings live in a file of their own, and travel in the backup as a section of their own. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreensaverBackupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.screensaverPreferences()

    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private val settings = SettingsRepository(InMemoryDataStore())
    private val resolver = FakeAppRowResolver()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LauncherDatabase::class.java).allowMainThreadQueries().build()
        dao = db.homeItemDao()
        prefs.edit().clear().commit()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() = db.close()

    private fun repo(homeItemDao: HomeItemDao = dao) =
        BackupRepository(context, homeItemDao, settings, resolver, ScreensaverSettingsStore(context))

    private fun doc(screensaver: Map<String, Any>?, vararg items: BackupItem) = BackupDocument(
        format = BackupCodec.FORMAT,
        appVersion = "test",
        createdAt = 0L,
        settings = mapOf("home_columns" to 4),
        homeItems = items.toList(),
        screensaver = screensaver,
    )

    private val chosen = mapOf<String, Any>(
        DreamPreferences.KEY_STYLE to "ring",
        DreamPreferences.KEY_SHOW_SECONDS to false,
        DreamPreferences.KEY_BRIGHTNESS_PERCENT to 40,
    )

    // --- The file --------------------------------------------------------------------------------

    @Test
    fun `the screensaver's settings travel through the file with their types`() {
        val back = BackupCodec.decode(BackupCodec.encode(doc(chosen)))

        assertThat(back.screensaver).containsExactlyEntriesIn(chosen)
    }

    @Test
    fun `a file without the section decodes, with no screensaver settings in it`() {
        val older = BackupCodec.encode(doc(screensaver = null))

        assertThat(older).doesNotContain("screensaver")
        assertThat(BackupCodec.decode(older).screensaver).isNull()
    }

    @Test
    fun `the section does not change the format, so an older launcher still reads the file`() {
        assertThat(BackupCodec.FORMAT).isEqualTo(4)
        assertThat(BackupCodec.decode(BackupCodec.encode(doc(chosen))).format).isEqualTo(4)
    }

    // --- Export and restore ------------------------------------------------------------------------

    @Test
    fun `export carries what the screensaver's file holds`() = runTest {
        prefs.edit().putString(DreamPreferences.KEY_STYLE, "edge").putBoolean(DreamPreferences.KEY_NIGHT_DIM, true).commit()

        val exported = repo().exportDocument(createdAt = 0L, appVersion = "test")

        assertThat(exported.screensaver).containsExactly(
            DreamPreferences.KEY_STYLE, "edge",
            DreamPreferences.KEY_NIGHT_DIM, true,
        )
    }

    @Test
    fun `restore writes the section into the screensaver's file`() = runTest {
        repo().restoreDocument(doc(chosen), installedApps = emptyList())

        assertThat(prefs.getString(DreamPreferences.KEY_STYLE, null)).isEqualTo("ring")
        assertThat(prefs.getBoolean(DreamPreferences.KEY_SHOW_SECONDS, true)).isFalse()
        assertThat(prefs.getInt(DreamPreferences.KEY_BRIGHTNESS_PERCENT, -1)).isEqualTo(40)
    }

    @Test
    fun `restoring a file without the section leaves the screensaver's settings alone`() = runTest {
        prefs.edit().putString(DreamPreferences.KEY_STYLE, "night").commit()

        repo().restoreDocument(doc(screensaver = null), installedApps = emptyList())

        assertThat(prefs.getString(DreamPreferences.KEY_STYLE, null)).isEqualTo("night")
    }

    private class FailingDao(private val inner: HomeItemDao) : HomeItemDao by inner {
        override suspend fun replaceLayout(items: List<HomeItemEntity>): Unit = throw IllegalStateException("disk full")
    }

    @Test
    fun `a failed layout write puts the screensaver's settings back too`() = runTest {
        prefs.edit().putString(DreamPreferences.KEY_STYLE, "night").commit()
        val backup = doc(chosen, BackupItem(1, -1, null, "a", "A", true, null, 0, 0, 0))

        val failure = runCatching { repo(FailingDao(dao)).restoreDocument(backup, listOf("a/A/0")) }

        assertThat(failure.isFailure).isTrue()
        // Exactly as before: the style back, and the keys the backup added gone again.
        assertThat(prefs.all).containsExactly(DreamPreferences.KEY_STYLE, "night")
    }

    // --- The battery-full notification's switch needs a permission the other phone may not hold -----

    private val withTheNotification = chosen + (DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION to true)

    @Test
    fun `the battery full switch is restored off on a phone that may not send notifications`() = runTest {
        repo().restoreDocument(doc(withTheNotification), installedApps = emptyList())

        assertThat(prefs.getBoolean(DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION, true)).isFalse()
        // The rest of the section is restored as it was.
        assertThat(prefs.getString(DreamPreferences.KEY_STYLE, null)).isEqualTo("ring")
    }

    @Test
    fun `the battery full switch is restored on where the permission is held`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        repo().restoreDocument(doc(withTheNotification), installedApps = emptyList())

        assertThat(prefs.getBoolean(DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION, false)).isTrue()
    }
}
