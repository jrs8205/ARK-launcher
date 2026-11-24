package org.arkikeskus.launcher.data.backup

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.arkikeskus.launcher.data.CustomIconSource
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.FakeAppRowResolver
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.IconPackRepository
import org.arkikeskus.launcher.data.LastBackup
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.WidgetIdReleaser
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

    private fun repo(
        homeItemDao: HomeItemDao = dao,
        drawerPictures: DrawerWallpaperStore? = null,
        widgetIds: WidgetIdReleaser? = null,
    ) = BackupRepository(context, homeItemDao, settings, resolver, ScreensaverSettingsStore(context), store, drawerPictures, widgetIds)

    private fun widget(pkg: String, id: Int?, cellX: Int) = HomeItemEntity(
        packageName = pkg, className = "", page = 0, cellX = cellX, cellY = 3, spanX = 2, spanY = 1,
        appWidgetId = id, widgetProvider = "$pkg/.Widget",
    )

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

    /**
     * The rows are replaced, and then the list of profiles cannot be written (a full disk): the other profiles
     * stay listed, with no rows. The restore says how many are left, so the screen does not call a half-done
     * restore done; what is left are empty profiles the user can delete.
     */
    @Test fun aRestoreThatCouldNotRemoveTheOtherProfilesSaysHowManyAreLeft() = runTest {
        twoProfiles()
        store.create("Evening", copyOf = null)
        val thenTheDiskFills = object : HomeItemDao by dao {
            override suspend fun replaceEveryProfile(items: List<HomeItemEntity>) {
                dao.replaceEveryProfile(items)
                main.failWrites = IOException("disk full")
            }
        }

        val result = repo(thenTheDiskFills).restoreDocument(backup, installed)

        assertThat(result.profilesLeft).isEqualTo(2)
        assertThat(result.restored).isEqualTo(2)
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        // What could be done was done: the rows are the backup's, in the profile that is on.
        assertThat(dao.getAllOfEveryProfile().map { it.packageName }).containsExactly("com.mail", "com.maps")
        assertThat(dao.getAllOfEveryProfile().map { it.homeProfileId }.toSet()).containsExactly(2L)
        // Once the disk has room again the profiles that are left can be deleted.
        main.failWrites = null
        assertThat(store.delete(1)).isTrue()
        assertThat(store.delete(3)).isTrue()
        assertThat(store.list()).containsExactly(HomeProfile(2, "Work"))
    }

    @Test fun aRestoreThatRemovedTheOtherProfilesLeavesNone() = runTest {
        twoProfiles()

        assertThat(repo().restoreDocument(backup, installed).profilesLeft).isEqualTo(0)
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

    /**
     * The widgets of the profiles that were not on go with their rows, and no home screen shows them: nothing
     * would free their ids before the next start, and their providers would go on updating widgets nobody
     * sees. The restore frees them itself. The ids of the profile that is on are the home screen's to free:
     * its host holds their views (it frees what no row holds when its rows change).
     */
    @Test fun aRestoreFreesTheWidgetIdsOfTheProfilesThatWereNotOn() = runTest {
        twoProfiles()
        dao.insert(widget("com.notes", 21, cellX = 0))
        inHomeProfile(1) {
            dao.insert(widget("com.clock", 11, cellX = 0))
            dao.insert(widget("com.cal", 12, cellX = 2))
            // A placeholder (a widget not set up yet) has no id to free.
            dao.insert(widget("com.news", null, cellX = 0).copy(cellY = 4))
        }
        val freed = ArrayList<Int>()

        repo(widgetIds = { freed += it }).restoreDocument(backup, installed)

        assertThat(freed).containsExactly(11, 12)
    }

    /** A restore that wrote nothing frees nothing: every profile still has its widgets. */
    @Test fun aFailedRestoreFreesNoWidgetId() = runTest {
        twoProfiles()
        inHomeProfile(1) { dao.insert(widget("com.clock", 11, cellX = 0)) }
        val failing = object : HomeItemDao by dao {
            override suspend fun replaceEveryProfile(items: List<HomeItemEntity>) = throw IOException("disk full")
        }
        val freed = ArrayList<Int>()

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { repo(failing, widgetIds = { freed += it }).restoreDocument(backup, installed) }
        }

        assertThat(freed).isEmpty()
        assertThat(inHomeProfile(1) { dao.getAll() }.mapNotNull { it.appWidgetId }).containsExactly(11)
    }

    // --- An export is of ONE profile, and says which ---------------------------------------------------

    /** The export says which profile it wrote, and notes its time as THAT profile's last export: a profile that
     *  was never exported does not show another profile's time. */
    @Test fun anExportNamesItsProfileAndNotesTheTimeAsThatProfilesOwn() = runTest {
        twoProfiles()
        var written: BackupDocument? = null

        val exported = repo().export(createdAt = { 77L }, appVersion = "test") { written = it }

        assertThat(exported).isEqualTo(ExportedProfile(id = 2, name = "Work", oneOfSeveral = true))
        assertThat(written!!.homeItems.map { it.packageName }).containsExactly("com.work", "com.chat")
        assertThat(settings.localLastBackupTime.first()).isEqualTo(77L)
        store.setActive(1)
        assertThat(settings.localLastBackupTime.first()).isEqualTo(0L)
        store.setActive(2)
        assertThat(settings.localLastBackupTime.first()).isEqualTo(77L)
    }

    @Test fun anExportOfTheOnlyProfileIsNotOneOfSeveral() = runTest {
        dao.insert(app("com.first", 0))

        val exported = repo().export(createdAt = { 5L }, appVersion = "test") {}

        assertThat(exported).isEqualTo(ExportedProfile(id = 1, name = "", oneOfSeveral = false))
        assertThat(settings.localLastBackupTime.first()).isEqualTo(5L)
    }

    /**
     * The profile is held while its document is read, and not while the file is written: a file service that
     * waits for its network must not keep every switch, page operation and pin request waiting with it. The
     * time is still the exported profile's, noted by its id, though another profile is on by then.
     */
    @Test fun theProfileIsNotHeldWhileTheFileIsWrittenAndTheTimeIsStillTheExportedProfiles() = runTest {
        twoProfiles()
        var switched: Unit? = null

        val exported = repo().export(createdAt = { 77L }, appVersion = "test") {
            switched = withTimeoutOrNull(1_000) { store.setActive(1) }
        }

        assertThat(switched).isNotNull()
        assertThat(exported.id).isEqualTo(2L)
        assertThat(store.current()).isEqualTo(1L)
        assertThat(settings.localLastBackupTime.first()).isEqualTo(0L)
        store.setActive(2)
        assertThat(settings.localLastBackupTime.first()).isEqualTo(77L)
    }

    /** The export is asked for the profile its button named. With another one on, nothing is written and no
     *  time is noted: the file would hold a profile the user did not choose. */
    @Test fun anExportAskedForAnotherProfileThanTheOneThatIsOnWritesNothing() = runTest {
        twoProfiles()
        var written = false

        assertThrows(ExportProfileChangedException::class.java) {
            kotlinx.coroutines.runBlocking { repo().export({ 77L }, "test", expectedProfile = 1) { written = true } }
        }

        assertThat(written).isFalse()
        assertThat(settings.localLastBackupTime.first()).isEqualTo(0L)
        store.setActive(1)
        assertThat(settings.localLastBackupTime.first()).isEqualTo(0L)
    }

    @Test fun anExportAskedForTheProfileThatIsOnGoesThrough() = runTest {
        twoProfiles()

        assertThat(repo().export({ 77L }, "test", expectedProfile = 2) {}.id).isEqualTo(2L)
    }

    /** A profile deleted while its file was being written takes no time: nothing is written for a profile
     *  that is gone (its store file would come back). */
    @Test fun anExportOfAProfileDeletedWhileItsFileWasWrittenNotesNoTime() = runTest {
        twoProfiles()

        val exported = repo().export(createdAt = { 77L }, appVersion = "test") {
            store.setActive(1)
            assertThat(store.delete(2)).isTrue()
        }

        assertThat(exported.id).isEqualTo(2L)
        assertThat(store.list().map { it.id }).containsExactly(1L)
        assertThat(files.stores.keys).doesNotContain(2L)
        assertThat(settings.localLastBackupTime.first()).isEqualTo(0L)
    }

    /**
     * Review round 5. An export to a slow place (a cloud folder) is still being written when another export is
     * made to another place and finishes at once. The time of the last export is the LATER export's: the slow
     * one finishing afterwards used to put its own, older time over it.
     */
    @Test fun ofTwoExportsThatOverlapTheTimeNotedIsTheLaterOnes() = kotlinx.coroutines.runBlocking<Unit> {
        twoProfiles()
        val repository = repo()
        val slowIsWriting = CompletableDeferred<Unit>()
        val slowMayFinish = CompletableDeferred<Unit>()

        val slow = async(Dispatchers.Default) {
            repository.export(createdAt = { 1_000L }, appVersion = "test", target = "cloud") {
                slowIsWriting.complete(Unit)
                slowMayFinish.await()
            }
        }
        withTimeout(5_000) { slowIsWriting.await() }
        repository.export(createdAt = { 2_000L }, appVersion = "test", target = "phone") {}
        assertThat(settings.localLastBackupTime.first()).isEqualTo(2_000L)

        slowMayFinish.complete(Unit)
        withTimeout(5_000) { slow.await() }

        assertThat(settings.localLastBackupTime.first()).isEqualTo(2_000L)
    }

    /**
     * Review round 5. Two exports to the SAME file, the first still being written: the second waits for it, so
     * the file ends up holding the later export. Written side by side, the slow first one finished last and
     * left the older copy in the file.
     */
    @Test fun ofTwoExportsToTheSameFileTheLaterOneIsWhatTheFileHolds() = kotlinx.coroutines.runBlocking<Unit> {
        twoProfiles()
        val repository = repo()
        val firstIsWriting = CompletableDeferred<Unit>()
        val firstMayFinish = CompletableDeferred<Unit>()
        var inTheFile = 0L

        val first = async(Dispatchers.Default) {
            repository.export(createdAt = { 1_000L }, appVersion = "test", target = "content://docs/backup.json") {
                firstIsWriting.complete(Unit)
                firstMayFinish.await()
                inTheFile = it.createdAt
            }
        }
        withTimeout(5_000) { firstIsWriting.await() }
        // Begun right here, on this thread, up to its first wait: it has asked for the file when the next line runs.
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            repository.export(createdAt = { 2_000L }, appVersion = "test", target = "content://docs/backup.json") { inTheFile = it.createdAt }
        }
        // The second gets the time to write, if it does not wait for the first.
        val wroteBesideTheFirst = withTimeoutOrNull(500) { second.await() } != null
        firstMayFinish.complete(Unit)
        withTimeout(5_000) { first.await(); second.await() }

        assertThat(wroteBesideTheFirst).isFalse()
        assertThat(inTheFile).isEqualTo(2_000L)
        assertThat(settings.localLastBackupTime.first()).isEqualTo(2_000L)
    }

    /**
     * Review round 6. Three exports of one profile. The first writes slowly to file A; the second, to A too,
     * waits for it; the third goes to file B and finishes at once. The second is the last to read its document,
     * so the time of the last export is the moment ITS document was read: not the moment it was asked for,
     * which is before the third's (the time of the last export went back by that, with no clock set back).
     */
    @Test fun anExportThatWaitedForItsFileIsTimedWhenItsDocumentIsRead() = kotlinx.coroutines.runBlocking<Unit> {
        twoProfiles()
        val repository = repo()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000L)
        val firstIsWriting = CompletableDeferred<Unit>()
        val firstMayFinish = CompletableDeferred<Unit>()
        var inFileA = 0L

        val first = async(Dispatchers.Default) {
            repository.export(createdAt = clock::get, appVersion = "test", target = "A") {
                firstIsWriting.complete(Unit)
                firstMayFinish.await()
                inFileA = it.createdAt
            }
        }
        withTimeout(5_000) { firstIsWriting.await() }
        clock.set(2_000L)
        // Asked for at 2 000, and begun right here, on this thread, up to its first wait: the wait for A, which the
        // first export holds. So it IS waiting there before the clock moves on, whatever the machine's load is
        // (started on another thread and given a moment, it could begin only at 4 000, and an export that read
        // its time before the wait would then pass this test too).
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            repository.export(createdAt = clock::get, appVersion = "test", target = "A") { inFileA = it.createdAt }
        }
        assertThat(second.isCompleted).isFalse()
        clock.set(3_000L)
        repository.export(createdAt = clock::get, appVersion = "test", target = "B") {}
        assertThat(settings.localLastBackupTime.first()).isEqualTo(3_000L)

        clock.set(4_000L)
        firstMayFinish.complete(Unit)
        withTimeout(5_000) { first.await(); second.await() }

        // The file's own time, the time shown and the export that was last are one export's.
        assertThat(inFileA).isEqualTo(4_000L)
        assertThat(settings.localLastBackupTime.first()).isEqualTo(4_000L)
    }

    /** Exports to different files do not wait for one another: a place that never answers must not keep the
     *  user from exporting anywhere else. */
    @Test fun anExportToAnotherFileDoesNotWaitForOneThatIsStillBeingWritten() = kotlinx.coroutines.runBlocking<Unit> {
        twoProfiles()
        val repository = repo()
        val stuckIsWriting = CompletableDeferred<Unit>()
        val stuckMayFinish = CompletableDeferred<Unit>()

        val stuck = async(Dispatchers.Default) {
            repository.export(createdAt = { 1_000L }, appVersion = "test", target = "cloud") {
                stuckIsWriting.complete(Unit)
                stuckMayFinish.await()
            }
        }
        withTimeout(5_000) { stuckIsWriting.await() }

        val other = withTimeoutOrNull(5_000) { repository.export(createdAt = { 2_000L }, appVersion = "test", target = "phone") {} }

        assertThat(other).isNotNull()
        stuckMayFinish.complete(Unit)
        withTimeout(5_000) { stuck.await() }
    }

    /** The time comes with the profile it is of, from one reading: a screen never shows one profile's time
     *  beside another's name. */
    @Test fun theLastExportComesWithTheProfileItIsOf() = runTest {
        twoProfiles()
        repo().export(createdAt = { 77L }, appVersion = "test") {}

        assertThat(settings.localLastBackup.first()).isEqualTo(LastBackup(homeProfileId = 2, timeMs = 77L, profileName = "Work"))
        store.setActive(1)
        assertThat(settings.localLastBackup.first()).isEqualTo(LastBackup(homeProfileId = 1, timeMs = 0L, profileName = ""))
    }

    /** A file that could not be written is no export: no time is noted. */
    @Test fun anExportWhoseFileCouldNotBeWrittenNotesNoTime() = runTest {
        twoProfiles()

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { repo().export(createdAt = { 77L }, appVersion = "test") { throw IOException("no space") } }
        }

        assertThat(settings.localLastBackupTime.first()).isEqualTo(0L)
    }

    /** The file is written; that its time could not be noted does not make the export fail. */
    @Test fun anExportWhoseTimeCouldNotBeNotedIsStillAnExport() = runTest {
        twoProfiles()

        val exported = repo().export(createdAt = { 77L }, appVersion = "test") {
            files.store(2).failWrites = IOException("disk full")
            main.failWrites = IOException("disk full")
        }

        assertThat(exported.id).isEqualTo(2L)
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

    /**
     * Review round 14 (P1). The profile the restore was confirmed for keeps its values in a store that cannot
     * be read. The restore's first reading of the settings left that profile for the first one (as a start
     * does) in the middle of the restore: the backup went into the first profile, the "other profiles" then
     * removed were the one the user had confirmed, and the result said restored. A profile is not left under
     * work that holds it: the restore is given the profile that can be read at its first step, sees that it
     * is not the one that was agreed to, and changes nothing.
     */
    @Test fun aRestoreConfirmedForAProfileThatCannotBeReadChangesNothing() = runTest {
        twoProfiles()
        val agreed = ConfirmedProfiles(activeId = 2, otherIds = setOf(1L))
        val ownWrites = files.store(2).writes
        files.store(2).failReads = IOException("Input/output error")

        val failure = runCatching { repo().restoreDocument(backup, installed, agreed) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ProfilesChangedException::class.java)
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(dao.getAllOfEveryProfile().map { it.packageName }).containsExactly("com.first", "com.chat", "com.work", "com.chat")
        // The first profile is on, as it was left to be, with its own look and not the backup's.
        assertThat(settings.settings.first().baseColour).isEqualTo("orange")
        assertThat(settings.settings.first().longPressScale).isEqualTo(1f)
        assertThat(files.store(2).writes).isEqualTo(ownWrites)
    }

    /**
     * And a store that reads when the restore begins and fails in the middle of it: the profile is the
     * restore's until the restore is done, so it is not left under it. The restore is told that its profile
     * cannot be read, at its first reading of the settings, which is before anything is changed.
     */
    @Test fun aRestoreWhoseProfileStopsReadingInTheMiddleChangesNothing() = runTest {
        twoProfiles()
        val agreed = ConfirmedProfiles(activeId = 2, otherIds = setOf(1L))
        // Read once more (the look a restore takes at its profile as it begins), and failing from then on.
        files.store(2).onNextRead = { files.store(2).failReads = IOException("Input/output error") }

        val failure = runCatching { repo().restoreDocument(backup, installed, agreed) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(dao.getAllOfEveryProfile().map { it.packageName }).containsExactly("com.first", "com.chat", "com.work", "com.chat")
        // Nothing of the backup is in the settings every profile shares, and the profile is as it was when
        // its store reads again.
        files.store(2).failReads = null
        store.setActive(2)
        assertThat(settings.settings.first().baseColour).isEqualTo("green")
        assertThat(settings.settings.first().longPressScale).isEqualTo(1f)
    }

    /** The same for an export: asked for the second profile, it wrote the first one's settings and home
     *  screen and said the file was the second's. Nothing is written now, and that is said. */
    @Test fun anExportAskedForAProfileThatCannotBeReadWritesNothing() = runTest {
        twoProfiles()
        files.store(2).failReads = IOException("Input/output error")
        var written: BackupDocument? = null

        val failure = runCatching {
            repo().export(createdAt = { 5L }, appVersion = "test", expectedProfile = 2) { written = it }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ExportProfileChangedException::class.java)
        assertThat(written).isNull()
    }

    /**
     * Review round 14. A restore removes the drawer pictures of the profiles that went, and did not look at
     * whether they went: a picture that could not be removed stayed on the disk under the id of a profile the
     * list no longer had, and the result said nothing was left. The result says how many such pictures there
     * are; the next restore finds each as a home screen no profile is listed for, asks, and removes it.
     */
    @Test fun aRestoreSaysHowManyPicturesOfTheProfilesThatWentAreStillOnTheDisk() = runTest {
        fun picture(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        var removable = false
        val pictures = DrawerWallpaperStore(
            context, Dispatchers.IO, remove = { if (removable) it.deleteRecursively() else !it.exists() },
        )
        pictures.set(picture(Color.WHITE), 1)
        twoProfiles()

        val result = repo(drawerPictures = pictures).restoreDocument(backup, installed)

        assertThat(store.list().map { it.id }).containsExactly(2L)
        assertThat(result.profilesLeft).isEqualTo(0)
        assertThat(result.picturesLeft).isEqualTo(1)
        assertThat(pictures.profilesWithPictures()).contains(1L)

        removable = true
        val again = repo(drawerPictures = pictures)
            .restoreDocument(backup, installed, ConfirmedProfiles(activeId = 2, otherIds = emptySet(), unlistedIds = setOf(1L)))
        assertThat(again.picturesLeft).isEqualTo(0)
        assertThat(pictures.profilesWithPictures()).doesNotContain(1L)
    }

    /**
     * Review round 15. What is counted is the pictures that are still there, not the removals that did not
     * end clean: a picture that went while its emptied folder could not be removed was counted as left, the
     * user was told that the next restore removes it, and the next restore finds no picture to ask about.
     */
    @Test fun aPictureThatWentWhileItsFolderStayedIsNotCountedAsLeft() = runTest {
        fun picture(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        // Every file in the folder goes; the folder itself does not.
        val pictures = DrawerWallpaperStore(
            context, Dispatchers.IO, remove = { folder -> folder.listFiles()?.forEach { it.delete() }; !folder.exists() },
        )
        pictures.set(picture(Color.WHITE), 1)
        twoProfiles()

        val result = repo(drawerPictures = pictures).restoreDocument(backup, installed)

        assertThat(pictures.profilesWithPictures()).doesNotContain(1L)
        assertThat(result.picturesLeft).isEqualTo(0)
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

    /**
     * Review round 9 (P1). After a damaged settings file the list is one profile again, while the other
     * profiles' home screens are still in the database. A restore replaces EVERY row there is (the backup's rows
     * come with their own ids), and its question named only the listed profiles: a home screen whose profile the
     * list had lost was deleted without ever having been in the question. The restore is held to the home
     * screens there are in the database too, and writes nothing when one of them was not agreed to.
     */
    @Test fun aRestoreThatWouldDeleteAHomeScreenNobodyAgreedToChangesNothing() = runTest {
        twoProfiles()
        // The list was lost with the settings file: one profile again; Work's rows are still there.
        main.updateData { androidx.datastore.preferences.core.emptyPreferences() }
        assertThat(store.list().map { it.id }).containsExactly(1L)
        val before = dao.getAllOfEveryProfile()
        assertThat(before.map { it.homeProfileId }.toSet()).containsExactly(1L, 2L)

        // The question named no other profile: the list has none.
        val failure = runCatching {
            repo().restoreDocument(backup, installed, ConfirmedProfiles(activeId = 1, otherIds = emptySet()))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ProfilesChangedException::class.java)
        assertThat(dao.getAllOfEveryProfile()).containsExactlyElementsIn(before)
    }

    /** Agreed to WITH that home screen (the question says that home screens no profile is listed for go too), the
     *  restore goes through, and what was left of the lost profile goes with its rows: its own values too. */
    @Test fun aRestoreAgreedToWithTheHomeScreensNoProfileIsListedForGoesThroughAndLeavesNothingOfThem() = runTest {
        twoProfiles()
        main.updateData { androidx.datastore.preferences.core.emptyPreferences() }
        assertThat(files.ids()).contains(2L)

        repo().restoreDocument(backup, installed, ConfirmedProfiles(activeId = 1, otherIds = emptySet(), unlistedIds = setOf(2L)))

        assertThat(dao.getAllOfEveryProfile().map { it.homeProfileId }.toSet()).containsExactly(1L)
        assertThat(store.list().map { it.id }).containsExactly(1L)
        assertThat(files.ids()).doesNotContain(2L)
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
    }

    /**
     * Review round 10 (P1). The question says two different things: "these profiles go" (by name) and "home
     * screens no longer in the list go". The restore compared the two as ONE set of ids. What a copy that died
     * left under an id is passed over when the next profile is made, so a new, named profile can take that
     * very id while the backup file is being read: the set was still {2}, and the restore deleted a profile
     * made after the question, on the word given for some left-over rows. Each set is held to its own.
     */
    @Test fun whatWasAgreedToAsUnlistedIsNoLeaveToDeleteAProfileMadeUnderItsIdSince() = runTest {
        dao.insert(app("com.first", 0))
        // What a copy that died left under id 2: rows, never listed. The question had the sentence for them.
        inHomeProfile(2) { dao.insert(app("com.left", 0)) }
        val agreed = ConfirmedProfiles(activeId = 1, otherIds = emptySet(), unlistedIds = setOf(2L))
        // While the file is read, a profile is made and takes that id.
        assertThat(store.create("Work", copyOf = null)?.id).isEqualTo(2L)
        val before = dao.getAllOfEveryProfile()

        val failure = runCatching { repo().restoreDocument(backup, installed, agreed) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ProfilesChangedException::class.java)
        assertThat(store.list()).containsExactly(HomeProfile(1, ""), HomeProfile(2, "Work")).inOrder()
        assertThat(dao.getAllOfEveryProfile()).containsExactlyElementsIn(before)
    }

    /** And the other way round: a profile named in the question whose listing was lost since is not deleted as
     *  "something unlisted" without the question having said so. */
    @Test fun aProfileAgreedToByNameIsNotDeletedAsUnlistedOnceItsListingIsLost() = runTest {
        twoProfiles()
        store.setActive(1)
        val agreed = ConfirmedProfiles(activeId = 1, otherIds = setOf(2L))
        main.updateData { androidx.datastore.preferences.core.emptyPreferences() }
        val before = dao.getAllOfEveryProfile()

        val failure = runCatching { repo().restoreDocument(backup, installed, agreed) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ProfilesChangedException::class.java)
        assertThat(dao.getAllOfEveryProfile()).containsExactlyElementsIn(before)
    }

    /**
     * Review round 10. A restore that died after its rows were written and before it removed what was left of
     * an unlisted profile (its own values, its drawer picture) left those on the disk for good: "unlisted" was
     * read from the rows, which were gone, so no later restore named them or removed them, and the pictures
     * those values name stayed in use. Unlisted is everything kept under an id the list does not name, so the
     * next restore's question has the sentence again and that restore finishes the job.
     */
    @Test fun whatARestoreThatDiedLeftOfAnUnlistedProfileIsFoundAndRemovedByTheNextRestore() = runTest {
        dao.insert(app("com.first", 0))
        val pictures = DrawerWallpaperStore(context)
        fun picture(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        // No row is under ids 2 and 3 any more: an own store is under 2, a drawer picture under 3.
        files.store(2).updateData { it.toMutablePreferences().apply { this[androidx.datastore.preferences.core.stringPreferencesKey("base_colour")] = "green" } }
        pictures.set(picture(Color.RED), 3)
        assertThat(pictures.profilesWithPictures()).contains(3L)

        // Agreed to without them, the restore refuses: it would remove what the question did not mention.
        val refused = runCatching {
            repo(drawerPictures = pictures).restoreDocument(backup, installed, ConfirmedProfiles(activeId = 1, otherIds = emptySet()))
        }.exceptionOrNull()
        assertThat(refused).isInstanceOf(ProfilesChangedException::class.java)

        repo(drawerPictures = pictures)
            .restoreDocument(backup, installed, ConfirmedProfiles(activeId = 1, otherIds = emptySet(), unlistedIds = setOf(2L, 3L)))

        assertThat(files.ids()).doesNotContain(2L)
        assertThat(pictures.profilesWithPictures()).doesNotContain(3L)
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
    }

    @Test fun aRestoreReportsSettingsThatCouldNotBeRemovedAndCanRetry() = runTest {
        files.store(2).updateData {
            it.toMutablePreferences().apply { this[androidx.datastore.preferences.core.stringPreferencesKey("base_colour")] = "green" }
        }
        val confirmed = ConfirmedProfiles(activeId = 1, otherIds = emptySet(), unlistedIds = setOf(2L))
        files.failDelete = java.io.IOException("file cannot be removed")
        val restored = repo().restoreDocument(backup, installed, confirmed)
        assertThat(restored.restored).isEqualTo(2)
        assertThat(store.unlistedStores()).containsExactly(2L)
        assertThat(restored.settingsCleanupIncomplete).isTrue()
        files.failDelete = null
        assertThat(repo().restoreDocument(backup, installed, confirmed).settingsCleanupIncomplete).isFalse()
        assertThat(store.unlistedStores()).isEmpty()
    }

    /** A restore that cannot find out what is kept under the unlisted ids does not go on as if there were
     *  nothing: it fails before its first write. */
    @Test fun aRestoreThatCannotListTheProfilesStoresWritesNothing() = runTest {
        twoProfiles()
        val before = dao.getAllOfEveryProfile()
        files.failIds = IOException("the folder could not be read")

        val failure = runCatching {
            repo().restoreDocument(backup, installed, ConfirmedProfiles(activeId = 2, otherIds = setOf(1L)))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(dao.getAllOfEveryProfile()).containsExactlyElementsIn(before)
        assertThat(settings.settings.first().baseColour).isEqualTo("green")
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L)
    }

    @Test fun aRestoreAgreedToWithTheProfilesThereAreGoesThrough() = runTest {
        twoProfiles()

        repo().restoreDocument(backup, installed, ConfirmedProfiles(activeId = 2, otherIds = setOf(1L)))

        assertThat(store.list()).containsExactly(HomeProfile(2, "Work"))
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
    }

    @Test fun aListedProfileWhoseSettingsSurviveCanBeCleanedOnRetry() = runTest {
        twoProfiles()
        store.setActive(1)
        val first = ConfirmedProfiles(activeId = 1, otherIds = setOf(2L))
        files.failDelete = IOException("cannot remove settings")
        val restored = repo().restoreDocument(backup, installed, first)
        assertThat(restored.restored).isEqualTo(2)
        assertThat(restored.settingsCleanupIncomplete).isTrue()
        assertThat(store.unlistedStores()).containsExactly(2L)
        files.failDelete = null
        val retry = ConfirmedProfiles(activeId = 1, otherIds = emptySet(), unlistedIds = setOf(2L))
        assertThat(repo().restoreDocument(backup, installed, retry).settingsCleanupIncomplete).isFalse()
        assertThat(store.unlistedStores()).isEmpty()
    }

    @Test fun aReadFailureAfterReplacingRowsStillReportsTheRestoreAndFreesRemovedWidgets() = runTest {
        twoProfiles()
        store.setActive(1)
        inHomeProfile(2) { dao.insert(widget("com.previous", 412, 0)) }
        val freed = mutableListOf<Int>()
        val confirmed = ConfirmedProfiles(activeId = 1, otherIds = setOf(2L))
        val interrupted = object : HomeItemDao by dao {
            override suspend fun replaceEveryProfile(items: List<HomeItemEntity>) {
                dao.replaceEveryProfile(items)
                main.onNextRead = { throw IOException("read after replacing rows") }
            }
        }
        val result = repo(interrupted, widgetIds = { freed += it }).restoreDocument(backup, installed, confirmed)
        assertThat(result.restored).isEqualTo(2)
        assertThat(dao.getAllOfEveryProfile().map { it.packageName }).containsExactly("com.mail", "com.maps")
        assertThat(freed).containsExactly(412)
    }
}
