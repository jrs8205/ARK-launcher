package org.arkikeskus.launcher.data.backup

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.CustomIconSource
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.FakeAppRowResolver
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.IconPackRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.local.inHomeProfile
import org.arkikeskus.launcher.data.profile.CountingStore
import org.arkikeskus.launcher.data.profile.FakeHomeProfileFiles
import org.arkikeskus.launcher.data.profile.HomeProfileStore
import org.arkikeskus.launcher.data.profile.ProfiledPreferences
import org.arkikeskus.launcher.data.screensaver.ScreensaverSettingsStore
import org.arkikeskus.launcher.model.HomeProfile
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/**
 * A restore replaces everything, and what a backup file describes is one home profile: after it the
 * launcher has that one profile, whatever it had before. A restore that fails leaves every profile as
 * it was. (The owner's decision of 2026-11-18.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupProfilesTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private val main = CountingStore()
    private val files = FakeHomeProfileFiles()
    private val view = ProfiledPreferences(main, files)
    private val store = HomeProfileStore(main, view, files)
    private val settings = SettingsRepository(view, view)
    private val resolver = FakeAppRowResolver().apply { ownSerial = 0L }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LauncherDatabase::class.java).allowMainThreadQueries().build()
        dao = db.homeItemDao(store)
    }

    @After fun tearDown() = db.close()

    private fun repo(homeItemDao: HomeItemDao = dao, drawerPictures: DrawerWallpaperStore? = null) =
        BackupRepository(context, homeItemDao, settings, resolver, ScreensaverSettingsStore(context), store, drawerPictures)

    private fun app(pkg: String, cellX: Int) =
        HomeItemEntity(packageName = pkg, className = "Main", page = 0, cellX = cellX, cellY = 0)

    private fun shortcut(pkg: String, id: String, cellX: Int) =
        HomeItemEntity(packageName = pkg, className = "", page = 0, cellX = cellX, cellY = 2, shortcutId = id)

    /** A backup of one profile: two apps (row ids 1 and 2) on a 5-column grid in blue. */
    private val backup = BackupDocument(
        BackupCodec.FORMAT, "test", 0L,
        mapOf("home_columns" to 5, "base_colour" to "blue", "long_press_scale" to 1.5),
        listOf(
            BackupItem(1, -1, null, "com.mail", "Main", true, null, 0, 0, 0),
            BackupItem(2, -1, null, "com.maps", "Main", true, null, 0, 1, 0),
        ),
        mainUserSerial = 0L,
    )
    private val installed = listOf("com.mail/Main/0", "com.maps/Main/0")

    /** Two profiles, the second one on. Both have rows whose ids the backup's rows also have. */
    private suspend fun twoProfiles() {
        settings.setBaseColour("orange")
        dao.insert(app("com.first", 0))
        dao.insert(shortcut("com.chat", "home-only", cellX = 1))
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setBaseColour("green")
        settings.setHomeColumns(3)
        dao.insert(app("com.work", 0))
        dao.insert(shortcut("com.chat", "work-only", cellX = 1))
    }

    @Test fun aRestoreLeavesOneProfile() = runTest {
        twoProfiles()

        repo().restoreDocument(backup, installed)

        // One profile: the one that was on, now with what the backup has.
        assertThat(store.list()).containsExactly(HomeProfile(2, "Work"))
        assertThat(store.current()).isEqualTo(2L)
        val now = settings.settings.first()
        assertThat(now.baseColour).isEqualTo("blue")
        assertThat(now.homeColumns).isEqualTo(5)
        assertThat(now.longPressScale).isEqualTo(1.5f)
        // No row of any other profile is left, and the restored rows are that profile's.
        val rows = dao.getAllOfEveryProfile()
        assertThat(rows.map { it.packageName }).containsExactly("com.mail", "com.maps")
        assertThat(rows.map { it.homeProfileId }.toSet()).containsExactly(2L)
        assertThat(dao.getAll()).hasSize(2)
        // The shortcuts of every profile the restore replaced are released in the system.
        assertThat(resolver.pins[("com.chat" to 0L)]).isEmpty()
        assertThat(files.stores).isEmpty()
    }

    @Test fun aRestoreIntoTheOnlyProfileIsWhatItAlwaysWas() = runTest {
        dao.insert(app("com.first", 0))

        repo().restoreDocument(backup, installed)

        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
        assertThat(dao.getAll().map { it.packageName }).containsExactly("com.mail", "com.maps")
        assertThat(dao.getAllOfEveryProfile().map { it.homeProfileId }.toSet()).containsExactly(1L)
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
    }

    @Test fun aFailedRestoreKeepsEveryProfile() = runTest {
        twoProfiles()
        val failing = object : HomeItemDao by dao {
            override suspend fun replaceEveryProfile(items: List<HomeItemEntity>) = throw IllegalStateException("disk full")
        }

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repo(failing).restoreDocument(backup, installed) }
        }

        assertThat(store.list().map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(store.current()).isEqualTo(2L)
        assertThat(settings.settings.first().baseColour).isEqualTo("green")
        assertThat(settings.settings.first().homeColumns).isEqualTo(3)
        assertThat(dao.getAll().map { it.packageName }).containsExactly("com.work", "com.chat")
        assertThat(inHomeProfile(1) { dao.getAll() }.map { it.packageName }).containsExactly("com.first", "com.chat")
        store.setActive(1)
        assertThat(settings.settings.first().baseColour).isEqualTo("orange")
    }

    @Test fun anExportIsTheProfileThatIsOn() = runTest {
        twoProfiles()

        val doc = repo().exportDocument(createdAt = 0L, appVersion = "test")

        assertThat(doc.format).isEqualTo(BackupCodec.FORMAT)
        assertThat(doc.homeItems.map { it.packageName }).containsExactly("com.work", "com.chat")
        assertThat(doc.settings["base_colour"]).isEqualTo("green")
        assertThat(doc.settings.keys.none { it.startsWith("home_profile") }).isTrue()
    }

    @Test fun aFolderPictureOfAProfileThatIsNotOnIsStillInUse() = runTest {
        val source = CustomIconSource(context, IconPackRepository(context))
        val icons = CustomIconStore(settings, source)
        fun picture() = source.files.save(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
        val home = picture()
        val work = picture()
        val orphan = picture()
        icons.setFolderIcon(FolderIconKey.home(4), home)
        store.create("Work", copyOf = null)
        store.setActive(2)
        icons.setFolderIcon(FolderIconKey.home(4), work)

        // The clean-up at a start, with profile 2 on.
        icons.deleteUnusedPictures(savedBefore = System.currentTimeMillis() + 60_000)

        assertThat(source.drawable(home)).isNotNull()
        assertThat(source.drawable(work)).isNotNull()
        assertThat(source.drawable(orphan)).isNull()
    }

    // The profile that is on must be one and the same for everything a restore or an export does.

    @Test fun aSwitchAskedForDuringARestoreWaitsForIt() = runTest {
        twoProfiles()
        // The profile is changed while the restore is between its rows and the list of profiles.
        val switching = object : HomeItemDao by dao {
            override suspend fun replaceEveryProfile(items: List<HomeItemEntity>) {
                dao.replaceEveryProfile(items)
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.setActive(1) }
            }
        }

        repo(switching).restoreDocument(backup, installed)

        // What was restored and what is left to show are one profile: not the rows in one and the list in another.
        val left = store.list().single()
        assertThat(store.current()).isEqualTo(left.id)
        assertThat(inHomeProfile(left.id) { dao.getAll() }.map { it.packageName }).containsExactly("com.mail", "com.maps")
        assertThat(settings.settings.first().homeColumns).isEqualTo(5)
    }

    @Test fun anExportIsOfOneProfileThoughASwitchIsAskedForMeanwhile() = runTest {
        twoProfiles()
        // The profile is changed between the export's settings and its rows.
        val switching = object : HomeItemDao by dao {
            override suspend fun getAllOnce(): List<HomeItemEntity> {
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.setActive(1) }
                return dao.getAllOnce()
            }
        }

        val doc = repo(switching).exportDocument(createdAt = 0L, appVersion = "test")

        assertThat(doc.settings["base_colour"]).isEqualTo("green")
        assertThat(doc.settings["home_columns"]).isEqualTo(3)
        assertThat(doc.homeItems.map { it.packageName }).containsExactly("com.work", "com.chat")
    }

    @Test fun aRestoreRemovesTheDrawerPicturesOfTheProfilesThatWent() = runTest {
        fun picture(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val pictures = DrawerWallpaperStore(context)
        pictures.set(picture(Color.WHITE), 1)
        val ofTheFirst = pictures.current.value!!.file
        twoProfiles()
        pictures.switchTo(2)
        pictures.set(picture(Color.RED), 2)
        val ofTheOneThatIsOn = pictures.current.value!!.file

        repo(drawerPictures = pictures).restoreDocument(backup, installed)

        // The first profile is gone, and nothing in the launcher could remove its picture any more.
        assertThat(ofTheFirst.exists()).isFalse()
        assertThat(ofTheOneThatIsOn.exists()).isTrue()
        assertThat(pictures.current.value?.file).isEqualTo(ofTheOneThatIsOn)
    }

    @Test fun aRestoreWhoseSettingsCannotBeSavedChangesNothing() = runTest {
        twoProfiles()
        // The profile that is on keeps its own values in a file of its own: that write fails, after the
        // shared ones went into the settings file.
        files.store(2).failWrites = IOException("no space left")

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { repo().restoreDocument(backup, installed) }
        }
        files.store(2).failWrites = null

        // Not half a restore: what every profile shares (the backup's press delay) is as it was, and so is the rest.
        assertThat(settings.settings.first().longPressScale).isEqualTo(1f)
        assertThat(settings.settings.first().baseColour).isEqualTo("green")
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(dao.getAll().map { it.packageName }).containsExactly("com.work", "com.chat")
        assertThat(inHomeProfile(1) { dao.getAll() }.map { it.packageName }).containsExactly("com.first", "com.chat")
    }

    /**
     * Review round 2: the restore's question names the profiles it deletes, and the file is read after the
     * answer. A profile made meanwhile was deleted with the rest, without a word. The restore is given the
     * profiles the question showed and writes nothing when they are not the profiles there are.
     */
    @Test fun aRestoreAgreedToBeforeAProfileWasMadeChangesNothing() = runTest {
        twoProfiles()
        // The question was answered when the profile that is on was the only one.
        val agreed = ConfirmedProfiles(activeId = 2, otherIds = emptySet())

        val failure = runCatching { repo().restoreDocument(backup, installed, agreed) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ProfilesChangedException::class.java)
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L)
        assertThat(settings.settings.first().baseColour).isEqualTo("green")
        assertThat(dao.getAllOfEveryProfile().map { it.packageName }).containsExactly("com.first", "com.chat", "com.work", "com.chat")
    }

    @Test fun aRestoreAgreedToInAnotherProfileThanTheOneThatIsOnChangesNothing() = runTest {
        twoProfiles()
        // The question showed the first profile as the one that stays; the second is on now.
        val agreed = ConfirmedProfiles(activeId = 1, otherIds = setOf(2L))

        val failure = runCatching { repo().restoreDocument(backup, installed, agreed) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ProfilesChangedException::class.java)
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L)
        assertThat(settings.settings.first().baseColour).isEqualTo("green")
    }

    @Test fun aRestoreAgreedToWithTheProfilesThereAreGoesThrough() = runTest {
        twoProfiles()

        repo().restoreDocument(backup, installed, ConfirmedProfiles(activeId = 2, otherIds = setOf(1L)))

        assertThat(store.list()).containsExactly(HomeProfile(2, "Work"))
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
    }
}
