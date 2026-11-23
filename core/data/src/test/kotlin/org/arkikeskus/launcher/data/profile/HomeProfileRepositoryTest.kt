package org.arkikeskus.launcher.data.profile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.arkikeskus.launcher.data.CustomIconSource
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.FakeAppRowResolver
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.IconPackRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.DOCK
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.local.inHomeProfile
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.HomeProfile
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Making, switching and deleting a home profile as a whole: its settings, its layout rows, the pictures
 * it uses and the shortcuts it has pinned are one thing to the user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeProfileRepositoryTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private val main = CountingStore()
    private val files = FakeHomeProfileFiles()
    private val view = ProfiledPreferences(main, files)
    private val store = HomeProfileStore(main, view, files)
    private val settings = SettingsRepository(view, view)
    private val resolver = FakeAppRowResolver()
    private lateinit var layout: HomeLayoutRepository
    private val drawerPictures = DrawerWallpaperStore(context)
    private val released = mutableListOf<Int>()
    private val releaseThreads = mutableListOf<Thread>()
    private lateinit var profiles: HomeProfileRepository

    /** The repository over a layout that reads and writes through [homeItemDao]: the way to do something in
     *  the middle of its work. */
    private fun repositoryOver(homeItemDao: HomeItemDao) = HomeProfileRepository(
        store, HomeLayoutRepository(db, homeItemDao, resolver), settings, resolver, drawerPictures,
    ) { released += it; releaseThreads += Thread.currentThread() }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LauncherDatabase::class.java).allowMainThreadQueries().build()
        dao = db.homeItemDao(store)
        layout = HomeLayoutRepository(db, dao, resolver)
        profiles = HomeProfileRepository(store, layout, settings, resolver, drawerPictures) { released += it; releaseThreads += Thread.currentThread() }
    }

    @After fun tearDown() = db.close()

    private fun app(pkg: String, cellX: Int, container: Long = HomeItemEntity.HOME) =
        HomeItemEntity(containerId = container, packageName = pkg, className = "Main", page = 0, cellX = cellX, cellY = 0)

    private fun widget(pkg: String, id: Int, cellX: Int) = HomeItemEntity(
        packageName = pkg, className = "", page = 0, cellX = cellX, cellY = 1, spanX = 2, spanY = 1,
        appWidgetId = id, widgetProvider = "$pkg/.Widget",
    )

    private fun shortcut(pkg: String, id: String, cellX: Int) =
        HomeItemEntity(packageName = pkg, className = "", page = 0, cellX = cellX, cellY = 2, shortcutId = id)

    private suspend fun rowsOf(profile: Long) = inHomeProfile(profile) { dao.getAll() }

    /** A new profile: a copy of the one that is on, as the page that shows it would ask, or an empty one. */
    private suspend fun created(name: String, copyCurrent: Boolean): HomeProfile =
        (profiles.create(name, copyOf = if (copyCurrent) store.current() else null) as HomeProfileRepository.Created.Done).profile

    private fun picture(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /**
     * Review round 10. What a restore's question calls "home screens no longer in the list" is everything kept
     * under an id the list does not name: rows, a profile's own values, its drawer picture. Read from the rows
     * alone, what a restore that died left behind (values and a picture, no rows any more) was never named again
     * and never removed.
     */
    @Test fun whatIsKeptUnderAnIdTheListDoesNotNameIsUnlistedWhateverItIs() = runTest {
        dao.insert(app("com.mail", 0))
        assertThat(profiles.unlistedHomeScreens.first()).isEmpty()

        inHomeProfile(4) { dao.insert(app("com.left", 0)) }
        files.store(5).updateData { it.toMutablePreferences().apply { this[androidx.datastore.preferences.core.stringPreferencesKey("base_colour")] = "green" } }
        drawerPictures.set(picture(Color.RED), 6)
        // The rows changed last but one; a listed profile's own things are never among them.
        created("Work", copyCurrent = false)

        assertThat(profiles.unlistedHomeScreens.first()).containsExactly(4L, 5L, 6L)
    }

    /** While the stores cannot be listed the question's value is what CAN be read (the rows), and reading it
     *  fails nothing: the restore reads the same for itself, and it is the one that must not go on. */
    @Test fun whatCannotBeListedIsLeftOutOfTheQuestionsValue() = runTest {
        dao.insert(app("com.mail", 0))
        inHomeProfile(4) { dao.insert(app("com.left", 0)) }
        files.store(5).updateData { it.toMutablePreferences().apply { this[androidx.datastore.preferences.core.stringPreferencesKey("base_colour")] = "green" } }
        files.failIds = java.io.IOException("the folder could not be read")

        assertThat(profiles.unlistedHomeScreens.first()).containsExactly(4L)
    }

    @Test fun aCopyHasTheLayoutTheLookAndTheFolderIconsOfItsSource() = runTest {
        settings.setBaseColour("blue")
        settings.setHomeColumns(5)
        dao.insert(app("com.mail", 0))
        dao.insert(app("com.phone", 0, container = DOCK))
        val folder = dao.insert(HomeItemEntity(folderName = "Tools", page = 0, cellX = 2, cellY = 3))
        dao.insert(app("com.calc", 0, container = folder))
        dao.insert(app("com.notes", 1, container = folder))
        val folderIcon = CustomIcon.FromPack("com.pack", "tools")
        settings.setFolderIcon(FolderIconKey.home(folder), folderIcon)
        val drawerFolder = settings.createDrawerFolder("Games")
        val drawerIcon = CustomIcon.FromPack("com.pack", "games")
        settings.setFolderIcon(FolderIconKey.drawer(drawerFolder), drawerIcon)

        val copy = created("Work", copyCurrent = true)

        assertThat(copy).isEqualTo(HomeProfile(2, "Work"))
        // Making it does not switch to it.
        assertThat(store.current()).isEqualTo(1L)
        fun List<HomeItemEntity>.apps() = filter { it.isApp }.map { it.packageName }
        assertThat(rowsOf(2).apps()).containsExactlyElementsIn(rowsOf(1).apps())
        val newFolder = rowsOf(2).single { it.isFolder }
        assertThat(newFolder.id).isNotEqualTo(folder)

        profiles.switchTo(2)
        val now = settings.settings.first()
        assertThat(now.baseColour).isEqualTo("blue")
        assertThat(now.homeColumns).isEqualTo(5)
        // The folder's own icon follows the folder's new row; the drawer folder keeps its id and its icon.
        assertThat(settings.folderIcons.first()).containsExactly(
            FolderIconKey.home(newFolder.id), folderIcon,
            FolderIconKey.drawer(drawerFolder), drawerIcon,
        )
        assertThat(settings.drawerFolders.first().map { it.name }).containsExactly("Games")
    }

    @Test fun anEmptyProfileHasNoRowsAndTheDefaultLook() = runTest {
        settings.setBaseColour("blue")
        settings.setLongPressScale(1.5f)
        dao.insert(app("com.mail", 0))

        val empty = created("Evening", copyCurrent = false)
        profiles.switchTo(empty.id)

        assertThat(rowsOf(empty.id)).isEmpty()
        assertThat(layout.homeItems.first()).isEmpty()
        val now = settings.settings.first()
        assertThat(now.baseColour).isEqualTo("orange")
        // How the phone behaves is the same in every profile.
        assertThat(now.longPressScale).isEqualTo(1.5f)
    }

    @Test fun switchingChangesWhatLayoutAndSettingsShowTogether() = runTest {
        settings.setHomeColumns(5)
        dao.insert(app("com.mail", 0))
        val work = created("Work", copyCurrent = false)
        inHomeProfile(work.id) { dao.insert(app("com.work", 0)) }

        profiles.switchTo(work.id)

        assertThat(profiles.activeId.first()).isEqualTo(work.id)
        assertThat(settings.settings.first().homeProfileId).isEqualTo(work.id)
        assertThat(settings.settings.first().homeColumns).isEqualTo(4)
        assertThat(layout.homeItems.first().map { it.packageName }).containsExactly("com.work")

        profiles.switchTo(1)
        assertThat(settings.settings.first().homeColumns).isEqualTo(5)
        assertThat(layout.homeItems.first().map { it.packageName }).containsExactly("com.mail")
    }

    @Test fun theListIsFullAtItsLimit() = runTest {
        repeat(HomeProfile.MAX_PROFILES - 1) { created("P$it", copyCurrent = false) }

        assertThat(profiles.create("One too many", copyOf = 1)).isEqualTo(HomeProfileRepository.Created.Full)
        assertThat(profiles.profiles.first()).hasSize(HomeProfile.MAX_PROFILES)
        assertThat(dao.profileIds()).isEmpty()
    }

    @Test fun deletingTheActiveProfileLandsOnItsNeighbour() = runTest {
        created("Work", copyCurrent = false)
        created("Evening", copyCurrent = false)
        profiles.switchTo(2)

        assertThat(profiles.delete(2)).isEqualTo(HomeProfileRepository.Deleted.Done)

        // The one after it; had it been the last, the one before.
        assertThat(store.current()).isEqualTo(3L)
        assertThat(store.list().map { it.id }).containsExactly(1L, 3L).inOrder()
        assertThat(profiles.delete(3)).isEqualTo(HomeProfileRepository.Deleted.Done)
        assertThat(store.current()).isEqualTo(1L)
    }

    @Test fun deletingAProfileRemovesItsRowsAndRePinsWhatItsShortcutsLeft() = runTest {
        dao.insert(shortcut("com.chat", "home-only", cellX = 0))
        val work = created("Work", copyCurrent = false)
        inHomeProfile(work.id) {
            dao.insert(app("com.work", 0))
            dao.insert(shortcut("com.chat", "work-only", cellX = 1))
            dao.insert(shortcut("com.maps", "office", cellX = 2))
        }

        assertThat(profiles.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)

        assertThat(rowsOf(work.id)).isEmpty()
        assertThat(rowsOf(1)).hasSize(1)
        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
        // The system holds what the profiles that are left still have.
        assertThat(resolver.pins[("com.chat" to 0L)]).containsExactly("home-only")
        assertThat(resolver.pins[("com.maps" to 0L)]).isEmpty()
    }

    @Test fun theLastProfileStays() = runTest {
        dao.insert(app("com.mail", 0))

        assertThat(profiles.delete(1)).isEqualTo(HomeProfileRepository.Deleted.Refused)
        assertThat(profiles.delete(7)).isEqualTo(HomeProfileRepository.Deleted.Refused)

        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
        assertThat(rowsOf(1)).hasSize(1)
    }

    // Settings asks for a profile and can be left the same moment: whoever asked is gone, and the work, which
    // is several stores' writes one after another, must still run to its end.

    @Test fun aCopyIsFinishedThoughItsCallerGoesAwayHalfWay() = runTest {
        dao.insert(app("com.mail", 0))
        lateinit var caller: Job
        // The caller goes away the moment the new profile is listed: its rows are not copied yet.
        main.afterWrite = { now ->
            if (HomeProfile.decode(now[stringPreferencesKey(HomeProfileKeys.LIST)]).size == 2) caller.cancel()
        }
        caller = launch { profiles.create("Work", copyOf = 1) }
        caller.join()
        main.afterWrite = null

        assertThat(store.list().map { it.name }).containsExactly("", "Work").inOrder()
        // Listed as a copy, it has the copy's home screen: not the source's look over an empty one.
        assertThat(rowsOf(2).map { it.packageName }).containsExactly("com.mail")
    }

    @Test fun aDeleteIsFinishedThoughItsCallerGoesAwayHalfWay() = runTest {
        dao.insert(app("com.mail", 0))
        val work = created("Work", copyCurrent = true)
        lateinit var caller: Job
        // The caller goes away the moment the profile's rows are gone: it is still in the list.
        val leaving = object : HomeItemDao by dao {
            override suspend fun deleteProfile(profile: Long) {
                dao.deleteProfile(profile)
                caller.cancel()
            }
        }
        val asked = repositoryOver(leaving)
        caller = launch { asked.delete(work.id) }
        caller.join()

        assertThat(rowsOf(work.id)).isEmpty()
        // Not left behind as a profile with an empty home screen.
        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
    }

    // The profile that is on must not change under work that is about "the profile that is on" from its first
    // step to its last: a tap on another profile waits for it.

    @Test fun aProfileBeingDeletedCannotBeSwitchedTo() = runTest {
        dao.insert(app("com.mail", 0))
        val work = created("Work", copyCurrent = true)
        // The user taps the profile again while its rows are going.
        val tapped = object : HomeItemDao by dao {
            override suspend fun deleteProfile(profile: Long) {
                dao.deleteProfile(profile)
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.setActive(work.id) }
            }
        }

        assertThat(repositoryOver(tapped).delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)

        // Not a profile that is on, listed, and has lost its home screen.
        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
        assertThat(store.current()).isEqualTo(1L)
    }

    @Test fun aCopyCannotBeSwitchedToBeforeItIsWhole() = runTest {
        dao.insert(app("com.mail", 0))
        dao.insert(app("com.maps", 1))
        // The user taps the new profile the moment it is listed.
        main.afterWrite = { now ->
            if (HomeProfile.decode(now[stringPreferencesKey(HomeProfileKeys.LIST)]).size == 2) {
                main.afterWrite = null
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.setActive(2) }
            }
        }
        val onWhileTheRowsWereCopied = mutableListOf<Long>()
        val watching = object : HomeItemDao by dao {
            override suspend fun insert(item: HomeItemEntity): Long {
                onWhileTheRowsWereCopied += store.current()
                return dao.insert(item)
            }
        }

        repositoryOver(watching).create("Work", copyOf = 1)

        assertThat(onWhileTheRowsWereCopied).containsExactly(1L, 1L)
        // The tap is honoured once the copy is whole.
        assertThat(store.current()).isEqualTo(2L)
        assertThat(rowsOf(2)).hasSize(2)
    }

    @Test fun aSmallerGridAndTheRowsItMovesAreOneProfiles() = runTest {
        val columns = intPreferencesKey("home_columns")
        settings.setHomeColumns(5)
        dao.insert(app("com.mail", 4))
        val work = created("Work", copyCurrent = true)
        // Another profile is tapped while the rows are being packed into the smaller grid.
        val tapped = object : HomeItemDao by dao {
            override suspend fun getContainerOrdered(containerId: Long): List<HomeItemEntity> {
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.setActive(work.id) }
                return dao.getContainerOrdered(containerId)
            }
        }

        assertThat(repositoryOver(tapped).setHomeColumns(4, profileId = 1)).isTrue()

        // The profile that was on: four columns, and its icon inside them.
        assertThat(view.profileValues(1)[columns]).isEqualTo(4)
        assertThat(rowsOf(1).single().cellX).isLessThan(4)
        // The other one keeps its grid and its icon in the fifth column.
        assertThat(view.profileValues(work.id)[columns]).isEqualTo(5)
        assertThat(rowsOf(work.id).single().cellX).isEqualTo(4)
    }

    /**
     * Review round 7. The steppers of the grid's size are on a Settings page that shows one profile's grid, and
     * what they ask can wait behind a switch. The hold keeps a switch out of the middle of the work, not out of
     * its start: a smaller grid asked for the first profile packed the icons of the one that had come on. The
     * change names its profile, and is not made for another.
     */
    @Test fun aGridSizeAskedForAProfileThatIsNotOnAnyMoreChangesNoGridAndMovesNoIcon() = runTest {
        val columns = intPreferencesKey("home_columns")
        val rows = intPreferencesKey("home_rows")
        settings.setHomeColumns(5)
        settings.setHomeRows(6)
        dao.insert(app("com.mail", 4))
        val work = created("Work", copyCurrent = true)
        // The switch the user asked for first is made before the steppers' changes get their turn.
        store.setActive(work.id)

        assertThat(profiles.setHomeColumns(4, profileId = 1)).isFalse()
        assertThat(profiles.setHomeRows(3, profileId = 1)).isFalse()

        for (profile in listOf(1L, work.id)) {
            assertThat(view.profileValues(profile)[columns]).isEqualTo(5)
            assertThat(view.profileValues(profile)[rows]).isEqualTo(6)
            assertThat(rowsOf(profile).single().cellX).isEqualTo(4)
        }
        // Asked for the profile that is on, it is made, for that one alone.
        assertThat(profiles.setHomeColumns(4, profileId = work.id)).isTrue()
        assertThat(view.profileValues(work.id)[columns]).isEqualTo(4)
        assertThat(rowsOf(work.id).single().cellX).isLessThan(4)
        assertThat(rowsOf(1).single().cellX).isEqualTo(4)
    }

    @Test fun deletingAProfileFreesTheWidgetIdsItHeld() = runTest {
        dao.insert(widget("com.clock", 11, cellX = 0))
        val work = created("Work", copyCurrent = false)
        inHomeProfile(work.id) {
            dao.insert(widget("com.cal", 12, cellX = 0))
            dao.insert(widget("com.news", 13, cellX = 2))
        }

        assertThat(profiles.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)

        // At once, not at the next start: their providers would go on updating widgets nobody shows.
        assertThat(released).containsExactly(12, 13)
    }

    /** Review round 2: freeing a widget id is a call into the system for every id, and Settings deletes a
     *  profile from the main thread. With several widgets that held the screen still. */
    @Test fun aDeletedProfilesWidgetIdsAreFreedOffTheCallersThread() = runTest {
        val work = created("Work", copyCurrent = false)
        inHomeProfile(work.id) {
            dao.insert(widget("com.cal", 12, cellX = 0))
            dao.insert(widget("com.news", 13, cellX = 2))
        }
        val caller = Thread.currentThread()

        assertThat(profiles.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)

        assertThat(released).containsExactly(12, 13)
        assertThat(releaseThreads).doesNotContain(caller)
    }

    @Test fun theSystemsPinnedSetsAreWrittenOffTheCallersThread() = runTest {
        dao.insert(shortcut("com.chat", "home-only", cellX = 1))
        val work = created("Work", copyCurrent = true)
        val caller = Thread.currentThread()

        assertThat(profiles.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)

        // Settings asks from the main thread, and these are calls into the system that can be slow.
        assertThat(resolver.pinThreads).isNotEmpty()
        assertThat(resolver.pinThreads).doesNotContain(caller)
    }

    @Test fun aRenameReachesTheList() = runTest {
        profiles.rename(1, "  Home  ")

        assertThat(profiles.profiles.first()).containsExactly(HomeProfile(1, "Home"))
    }

    @Test fun aCopiedFoldersPictureSurvivesItsRemovalInTheOtherProfile() = runTest {
        val source = CustomIconSource(context, IconPackRepository(context))
        val icons = CustomIconStore(settings, source)
        val folder = dao.insert(HomeItemEntity(folderName = "Tools", page = 0, cellX = 2, cellY = 3))
        dao.insert(app("com.calc", 0, container = folder))
        dao.insert(app("com.notes", 1, container = folder))
        val picked = source.files.save(picture(Color.RED))
        icons.setFolderIcon(FolderIconKey.home(folder), picked)
        val copy = created("Work", copyCurrent = true)

        // The folder's picture is cleared in the profile that is on: the copy's folder still shows it.
        icons.setFolderIcon(FolderIconKey.home(folder), null)
        assertThat(source.drawable(picked)).isNotNull()

        // Cleared in the other one too, nothing uses the file any more.
        profiles.switchTo(copy.id)
        val copiedFolder = rowsOf(copy.id).single { it.isFolder }
        icons.setFolderIcon(FolderIconKey.home(copiedFolder.id), null)
        assertThat(source.drawable(picked)).isNull()
    }

    @Test fun aCopyGetsTheDrawersPictureAndADeletedProfilesPictureGoes() = runTest {
        drawerPictures.set(picture(Color.WHITE), 1)
        val original = drawerPictures.current.value!!
        val copy = created("Work", copyCurrent = true)
        val empty = created("Evening", copyCurrent = false)

        drawerPictures.switchTo(empty.id)
        assertThat(drawerPictures.current.value).isNull()
        drawerPictures.switchTo(copy.id)
        val copied = drawerPictures.current.value!!
        assertThat(copied.luminance).isEqualTo(original.luminance)
        assertThat(copied.file).isNotEqualTo(original.file)

        drawerPictures.switchTo(1)
        assertThat(profiles.delete(copy.id)).isEqualTo(HomeProfileRepository.Deleted.Done)

        assertThat(copied.file.exists()).isFalse()
        assertThat(drawerPictures.current.value!!.file.exists()).isTrue()
    }

    // --- A copy is a profile only when it is whole (review round 4) ------------------------------------

    /** A file where the new profile's picture folder should be: its picture cannot be copied. */
    private fun blockThePictureOf(profile: Long) =
        File(context.filesDir, "drawer_wallpaper_$profile").apply { deleteRecursively(); writeText("in the way") }

    /**
     * The copy's picture cannot be written. There is then no new profile: it used to be listed before its rows
     * and its picture were made, the picture's failure was swallowed, and the list offered a profile that was
     * not what its source is. What had been made of it is gone, and the next profile gets the id and is whole.
     */
    @Test fun aCopyThatCannotBeMadeWholeLeavesNoProfile() = runTest {
        dao.insert(app("com.mail", 0))
        drawerPictures.set(picture(Color.WHITE), 1)
        // The picture's folder is blocked once the copy's rows are being written (the making first clears what
        // an earlier attempt left under the id, which would take a block set before it away).
        var inTheWay: File? = null
        val blocking = object : HomeItemDao by dao {
            override suspend fun insert(item: HomeItemEntity): Long {
                if (inTheWay == null) inTheWay = blockThePictureOf(2)
                return dao.insert(item)
            }
        }

        assertThat(repositoryOver(blocking).create("Work", copyOf = 1)).isEqualTo(HomeProfileRepository.Created.Failed)

        assertThat(store.list().map { it.id }).containsExactly(1L)
        assertThat(rowsOf(2)).isEmpty()
        assertThat(files.stores.keys).doesNotContain(2L)
        inTheWay!!.delete()
        val work = created("Work", copyCurrent = true)
        assertThat(work.id).isEqualTo(2L)
        assertThat(rowsOf(2).map { it.packageName }).containsExactly("com.mail")
    }

    /** Runs what is dispatched to it one piece at a time, when asked, on the asker's thread. */
    private class OneAtATime : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        fun runOne(): Boolean = queue.poll()?.also { it.run() } != null

        /** Whether a piece of work is waiting to be run. */
        val waiting: Boolean get() = queue.isNotEmpty()
    }

    /** While its rows are there and its picture is still being copied, the copy is not in the list: a process
     *  that dies now leaves no profile with its source's look over half a home screen. */
    @Test fun aCopyIsNotListedBeforeItIsWhole() = runBlocking<Unit> {
        val io = OneAtATime()
        val paced = DrawerWallpaperStore(context, io)
        val repository = HomeProfileRepository(store, layout, settings, resolver, paced) {}
        dao.insert(app("com.mail", 0))

        val making = async(Dispatchers.Default) { repository.create("Work", copyOf = 1) }
        val listedWhileItsPictureWasStillToCome = try {
            // The picture store's first two pieces of work: saying which profiles it keeps a picture for (an id
            // something is kept under is not given), and clearing what an earlier attempt left under the id.
            repeat(2) { withTimeout(5_000) { while (!io.runOne()) delay(5) } }
            withTimeout(5_000) { while (rowsOf(2).isEmpty()) delay(5) }
            // Its rows are copied; the copy of its picture waits in the paced store's queue.
            store.list().map { it.id }
        } finally {
            // Whatever was seen, the making is let through to its end: it cannot be cancelled, and this test
            // would wait for it for ever.
            withTimeout(5_000) {
                while (!making.isCompleted) {
                    io.runOne()
                    delay(5)
                }
            }
        }

        assertThat(listedWhileItsPictureWasStillToCome).containsExactly(1L)
        assertThat((making.await() as HomeProfileRepository.Created.Done).profile.id).isEqualTo(2L)
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    /** An attempt that died half-way left its values (which say it was being made), rows and a picture under the
     *  id the next profile gets (an id is given again until a profile is listed with it): the next profile starts
     *  without them, a copy or an empty one. */
    @Test fun whatAnAttemptThatDiedLeftUnderAnIdIsNotInTheNextProfileOfThatId() = runTest {
        assertThat(store.prepare("Dead", copyOf = 1)!!.id).isEqualTo(2L)
        // The process died here: nothing in memory knows of the attempt any more.
        view.made(2)
        inHomeProfile(2) { dao.insert(app("com.ghost", 3)) }
        val leftover = File(context.filesDir, "drawer_wallpaper_2").apply { mkdirs() }
        File(leftover, "ghost-0.500.jpg").writeBytes(ByteArray(10))

        val evening = created("Evening", copyCurrent = false)

        assertThat(evening.id).isEqualTo(2L)
        assertThat(rowsOf(2)).isEmpty()
        assertThat(leftover.listFiles().orEmpty().filter { it.extension == "jpg" }).isEmpty()
    }

    /**
     * Review round 4. A picture is picked for a profile while the system's picker is open, and the profile can
     * be deleted before the picker answers. Nothing is kept for a profile that is gone: no picture is saved for
     * it, and one saved while it was being deleted goes with it (nothing in the launcher could remove it later).
     */
    @Test fun aPictureForAProfileThatIsGoneIsNotKept() = runTest {
        val work = created("Work", copyCurrent = false)
        val folder = File(context.filesDir, "drawer_wallpaper_${work.id}")
        var saves = 0

        // Deleted while the picture was being read and saved: the save brings its folder back.
        val whileSaving = profiles.setDrawerPicture(work.id) { store, pick ->
            assertThat(profiles.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)
            saves++
            store.set(picture(Color.WHITE), pick)
        }
        assertThat(whileSaving).isFalse()
        assertThat(folder.exists()).isFalse()

        // Deleted before the picker answered: the picture is not even read.
        val after = profiles.setDrawerPicture(work.id) { store, pick ->
            saves++
            store.set(picture(Color.WHITE), pick)
        }
        assertThat(after).isFalse()
        assertThat(saves).isEqualTo(1)
        assertThat(folder.exists()).isFalse()
    }

    @Test fun aPictureForAProfileThatIsThereIsSavedThoughItIsNotTheOneThatIsOn() = runTest {
        val work = created("Work", copyCurrent = false)

        assertThat(profiles.setDrawerPicture(work.id) { store, pick -> store.set(picture(Color.BLACK), pick) }).isTrue()

        assertThat(drawerPictures.current.value).isNull()
        drawerPictures.switchTo(work.id)
        assertThat(drawerPictures.current.value).isNotNull()
    }

    /**
     * Review round 5. The user picks a picture and then removes the profile's picture, before the pick has got
     * anywhere (it waits for the list of profiles first). The removal is the LATER action, and it stays: the
     * pick took its place among the user's actions when it was made, not when its save began.
     */
    @Test fun aPictureRemovedAfterItWasPickedStaysRemoved() = runTest {
        val work = created("Work", copyCurrent = false)
        assertThat(profiles.setDrawerPicture(work.id) { store, pick -> store.set(picture(Color.BLACK), pick) }).isTrue()

        // The pick's first step waits for the list, and the user removes the picture meanwhile.
        main.onNextRead = { drawerPictures.clear(work.id) }
        val picked = profiles.setDrawerPicture(work.id) { store, pick -> store.set(picture(Color.WHITE), pick) }

        assertThat(picked).isFalse()
        drawerPictures.switchTo(work.id)
        assertThat(drawerPictures.current.value).isNull()
        val folder = File(context.filesDir, "drawer_wallpaper_${work.id}")
        assertThat(folder.listFiles().orEmpty().filter { it.extension == "jpg" }).isEmpty()
    }

    /**
     * Review round 5. A deletion removes the profile's picture folder and only then takes the profile out of
     * the list. The picker answering between the two saw the profile listed before its save and after it, and
     * left a picture in the folder that had just been cleared, for a profile that was gone a moment later:
     * nothing in the launcher could remove it. The pick's last look at the list waits for a deletion under way.
     */
    @Test fun aPictureSavedWhileItsProfileIsBeingDeletedGoesWithIt() = runBlocking<Unit> {
        val io = OneAtATime()
        val paced = DrawerWallpaperStore(context, io)
        val repository = HomeProfileRepository(store, layout, settings, resolver, paced) {}
        val work = created("Work", copyCurrent = false)
        val folder = File(context.filesDir, "drawer_wallpaper_${work.id}")
        val picked = CompletableDeferred<Boolean>()
        val prepared = CompletableDeferred<Unit>()
        val test = this

        val deleting = async(Dispatchers.Default) { repository.delete(work.id) }
        try {
            // The deletion stands at the removal of the picture folder (the paced store's queue).
            withTimeout(5_000) { while (!io.waiting) delay(5) }
            // The list's next reader is the deletion itself, right before it takes the profile out of the
            // list: there the picker answers.
            main.onNextRead = {
                test.launch(Dispatchers.Default) {
                    picked.complete(repository.setDrawerPicture(work.id) { store, pick -> store.set(picture(Color.WHITE), pick).also { prepared.complete(Unit) } })
                }
                // The candidate is prepared while the profile is still listed...
                withTimeout(5_000) { prepared.await() }
                // ...and the pick gets the time to come to its end, if it can before the deletion has.
                withTimeoutOrNull(300) { picked.await() }
            }
        } finally {
            withTimeout(10_000) {
                while (!deleting.isCompleted || !picked.isCompleted) {
                    io.runOne()
                    delay(5)
                }
            }
        }

        assertThat(deleting.await()).isEqualTo(HomeProfileRepository.Deleted.Done)
        assertThat(store.list().map { it.id }).containsExactly(1L)
        assertThat(picked.await()).isFalse()
        assertThat(folder.exists()).isFalse()
    }

    /**
     * Review round 6. The same moment as above, and the caller is CANCELLED while its pick waits for the
     * deletion to end: Settings was left, and its coroutines go with it. The picture is saved by then, and
     * the look that takes it away for a profile that is gone must still be made: it used to be skipped with
     * the cancellation, and the file stayed in the folder of a profile that no longer existed.
     */
    @Test fun aPictureSavedForAProfileBeingDeletedGoesThoughItsCallerWasCancelled() = runBlocking<Unit> {
        val io = OneAtATime()
        val paced = DrawerWallpaperStore(context, io)
        val repository = HomeProfileRepository(store, layout, settings, resolver, paced) {}
        val work = created("Work", copyCurrent = false)
        val folder = File(context.filesDir, "drawer_wallpaper_${work.id}")
        var picking: Job? = null
        val prepared = CompletableDeferred<Unit>()
        val test = this

        val deleting = async(Dispatchers.Default) { repository.delete(work.id) }
        try {
            withTimeout(5_000) { while (!io.waiting) delay(5) }
            main.onNextRead = {
                picking = test.launch(Dispatchers.Default) {
                    repository.setDrawerPicture(work.id) { store, pick -> store.set(picture(Color.WHITE), pick).also { prepared.complete(Unit) } }
                }
                withTimeout(5_000) { prepared.await() }
                // The pick has its picture saved and has come to wait for the deletion; Settings is left now.
                delay(150)
                picking!!.cancel()
                delay(150)
            }
        } finally {
            withTimeout(10_000) {
                while (!deleting.isCompleted || picking?.isCompleted != true) {
                    io.runOne()
                    delay(5)
                }
            }
        }

        assertThat(deleting.await()).isEqualTo(HomeProfileRepository.Deleted.Done)
        assertThat(picking!!.isCancelled).isTrue()
        assertThat(folder.exists()).isFalse()
    }

    /**
     * Review round 6. A copy's folders show the pictures of its source's folders, and the copy's own values
     * name them from the moment they are written, which is before the copy is listed. A picture the source's
     * folder gives up in that moment is still in use: only listed profiles were asked, the file was deleted,
     * and the copy came out with a folder that had lost its picture.
     */
    @Test fun aFolderPictureGivenUpWhileACopyIsBeingMadeStaysForTheCopy() = runTest {
        val source = CustomIconSource(context, IconPackRepository(context))
        val icons = CustomIconStore(settings, source)
        val games = settings.createDrawerFolder("Games")
        val picked = source.files.save(picture(Color.RED))
        icons.setFolderIcon(FolderIconKey.drawer(games), picked)
        dao.insert(app("com.mail", 0))
        // While the copy's rows are written (its own values are there, it is not listed yet), the source's
        // folder gets another icon.
        var replaced = false
        val meanwhile = object : HomeItemDao by dao {
            override suspend fun insert(item: HomeItemEntity): Long {
                if (!replaced) {
                    replaced = true
                    assertThat(store.list().map { it.id }).containsExactly(1L)
                    icons.setFolderIcon(FolderIconKey.drawer(games), CustomIcon.FromPack("com.pack", "games"))
                }
                return dao.insert(item)
            }
        }

        val copy = (repositoryOver(meanwhile).create("Work", copyOf = 1) as HomeProfileRepository.Created.Done).profile

        assertThat(replaced).isTrue()
        assertThat(source.drawable(picked)).isNotNull()
        profiles.switchTo(copy.id)
        assertThat(settings.folderIcons.first()[FolderIconKey.drawer(games)]).isEqualTo(picked)
    }

    /**
     * Review round 8 (P1). The list of profiles and the memory of the ids given are in the settings file, and a
     * settings file that was damaged is read as empty: one unnamed profile again. The other profiles' layouts,
     * their own values and their pictures are kept elsewhere and are still there. The next profile used to get the
     * id of one of them: its values were written over that profile's and its home screen was cleared as the
     * left-overs of an attempt. Nothing that is kept under an id is touched now: the new profile gets another id.
     */
    @Test fun aProfileWhoseListingWasLostKeepsItsHomeScreenItsValuesAndItsPictureWhenTheNextOneIsMade() = runTest {
        val colour = androidx.datastore.preferences.core.stringPreferencesKey("base_colour")
        val work = created("Work", copyCurrent = false)
        val evening = created("Evening", copyCurrent = false)
        val night = created("Night", copyCurrent = false)
        // Work has a home screen, Evening a look of its own, Night only a picture.
        inHomeProfile(work.id) { dao.insert(app("com.chat", 0)) }
        store.setActive(evening.id)
        settings.setBaseColour("green")
        store.setActive(1)
        val nightsPicture = File(File(context.filesDir, "drawer_wallpaper_${night.id}").apply { mkdirs() }, "night-0.500.jpg")
        nightsPicture.writeBytes(ByteArray(10))

        // The settings file is damaged and replaced by an empty one.
        main.updateData { androidx.datastore.preferences.core.emptyPreferences() }
        assertThat(store.list().map { it.id }).containsExactly(1L)

        val made = created("New", copyCurrent = false)

        assertThat(made.id).isEqualTo(5L)
        assertThat(rowsOf(work.id).map { it.packageName }).containsExactly("com.chat")
        assertThat(view.profileValues(evening.id)[colour]).isEqualTo("green")
        assertThat(nightsPicture.exists()).isTrue()
        nightsPicture.parentFile!!.deleteRecursively()
    }

    /**
     * Review round 8. "A copy of the current profile" is confirmed on a page that shows one profile, and the
     * making can get its turn after a switch that was waiting. The copy is of the profile the page named, not of
     * the one that came on; and a source that is gone by then makes nothing.
     */
    @Test fun aCopyIsOfTheProfileThePageNamedThoughAnotherOneIsOnWhenItIsMade() = runTest {
        val colour = androidx.datastore.preferences.core.stringPreferencesKey("base_colour")
        settings.setBaseColour("blue")
        dao.insert(app("com.mail", 0))
        val work = created("Work", copyCurrent = false)
        inHomeProfile(work.id) { dao.insert(app("com.chat", 2)) }
        store.setActive(work.id)
        settings.setBaseColour("green")

        val copy = (profiles.create("Copy", copyOf = 1) as HomeProfileRepository.Created.Done).profile

        assertThat(rowsOf(copy.id).map { it.packageName }).containsExactly("com.mail")
        assertThat(view.profileValues(copy.id)[colour]).isEqualTo("blue")
        assertThat(store.current()).isEqualTo(work.id)
        assertThat(profiles.create("Of nothing", copyOf = 99)).isEqualTo(HomeProfileRepository.Created.Failed)
        assertThat(store.list().map { it.id }).containsExactly(1L, work.id, copy.id).inOrder()
    }

    /**
     * Review round 8. The icon the user picked for a pinned shortcut is one value for every profile. A profile's
     * deletion left it behind when the shortcut was on that profile's home screen alone: pinned again later, the
     * shortcut wore the old icon. It goes with the shortcut's last copy, and stays while another profile has one.
     */
    @Test fun aDeletedProfilesShortcutLosesItsOwnIconUnlessAnotherProfileStillHasTheShortcut() = runTest {
        val work = created("Work", copyCurrent = false)
        inHomeProfile(work.id) {
            dao.insert(shortcut("com.mail", "inbox", 0))
            dao.insert(shortcut("com.chat", "team", 1))
        }
        dao.insert(shortcut("com.chat", "team", 0))
        val onlyThere = org.arkikeskus.launcher.data.ShortcutIconKey.of("com.mail", "inbox", 0)
        val inBoth = org.arkikeskus.launcher.data.ShortcutIconKey.of("com.chat", "team", 0)
        settings.setShortcutIcon(onlyThere, CustomIcon.FromPack("com.pack", "letter"))
        settings.setShortcutIcon(inBoth, CustomIcon.FromPack("com.pack", "people"))

        assertThat(profiles.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)

        assertThat(settings.shortcutIcons.first().keys).containsExactly(inBoth)
    }

    /**
     * Review round 8. Deleting the profile whose values live in the settings file moves the next one's values
     * there, writes the list, and then drops that profile's own store, which nothing reads any more. A store
     * that could not be removed threw: the deletion, which had happened, was reported as unfinished.
     */
    @Test fun aDeletionIsDoneThoughTheStoreNobodyReadsAnyMoreCannotBeRemoved() = runTest {
        val work = created("Work", copyCurrent = false)
        store.setActive(work.id)
        files.failDelete = java.io.IOException("the file is in use")

        assertThat(profiles.delete(1)).isEqualTo(HomeProfileRepository.Deleted.Done)

        assertThat(store.list().map { it.id }).containsExactly(work.id)
        assertThat(store.current()).isEqualTo(work.id)
    }

    /**
     * Review round 7. A deletion takes the profile's rows in one transaction and then pins the shortcuts they
     * had again as the other profiles still have them, reading each app's set from the rows that are left. A
     * reading that failed there threw before the deletion had been told its rows were gone: it answered that
     * the profile was whole (its home screen was empty), kept it in the list and did not let go of its
     * widgets. The pins are a separate matter: the deletion is finished, and the other app's set is pinned.
     */
    @Test fun aDeletionWhoseShortcutsCannotBePinnedAgainIsStillFinishedAndLetsGoOfItsWidgets() = runTest {
        val work = created("Work", copyCurrent = false)
        inHomeProfile(work.id) {
            dao.insert(shortcut("com.mail", "inbox", 0))
            dao.insert(shortcut("com.chat", "team", 1))
            dao.insert(widget("com.clock", 51, 0))
        }
        dao.insert(shortcut("com.chat", "family", 0))
        val oneSetUnreadable = object : HomeItemDao by dao {
            override suspend fun shortcutIdsOfEveryProfile(pkg: String, user: Long): List<String> {
                if (pkg == "com.mail") throw java.io.IOException("disk I/O error")
                return dao.shortcutIdsOfEveryProfile(pkg, user)
            }
        }

        assertThat(repositoryOver(oneSetUnreadable).delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)

        assertThat(store.list().map { it.id }).containsExactly(1L)
        assertThat(rowsOf(work.id)).isEmpty()
        assertThat(released).containsExactly(51)
        assertThat(resolver.pins["com.chat" to 0L]).containsExactly("family")
    }

    @Test fun anUnreadableSettingsFileRefusesCreationBeforePreparingAProfile() = runTest {
        main.failReads = java.io.IOException("unreadable settings")
        assertThat(profiles.create("New", null)).isEqualTo(HomeProfileRepository.Created.Failed)
        main.failReads = null
        assertThat(store.list().map { it.id }).containsExactly(1L)
        assertThat(files.ids()).isEmpty()
    }

    @Test fun anUnreadableSettingsFileRefusesDeletionAndKeepsTheProfile() = runTest {
        val other = created("Other", copyCurrent = false)
        inHomeProfile(other.id) { dao.insert(app("com.mail", 0)) }
        main.failReads = java.io.IOException("unreadable settings")
        assertThat(profiles.delete(other.id)).isEqualTo(HomeProfileRepository.Deleted.Failed)
        main.failReads = null
        assertThat(store.list()).contains(other)
        assertThat(rowsOf(other.id).single().packageName).isEqualTo("com.mail")
    }

    /**
     * Review round 6. Switching, renaming and deleting write the list of profiles, and that write fails as any
     * other can (a full disk). Each says so as its result and throws nothing: Settings asks without catching,
     * and an exception there took the launcher down. A deletion says which of two things is so: nothing of the
     * profile has gone, or its home screen has and it is still listed, to be deleted again.
     */
    @Test fun aSwitchARenameAndADeletionTheStorageRefusesSaySoAndThrowNothing() = runTest {
        val work = created("Work", copyCurrent = false)
        dao.insert(app("com.mail", 0))
        inHomeProfile(work.id) {
            dao.insert(app("com.chat", 0))
            dao.insert(widget("com.clock", 41, 0))
        }
        main.failWrites = java.io.IOException("No space left on device")

        assertThat(profiles.switchTo(work.id)).isFalse()
        assertThat(profiles.rename(work.id, "Job")).isFalse()
        // Deleting the profile that is on begins by switching away from it: nothing of it has gone.
        assertThat(profiles.delete(1)).isEqualTo(HomeProfileRepository.Deleted.Failed)
        assertThat(rowsOf(1).map { it.packageName }).containsExactly("com.mail")
        // Deleting another one takes its rows and then cannot write the list: it is left listed, empty, and
        // its widget is let go of all the same.
        assertThat(profiles.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.LeftEmpty)
        assertThat(rowsOf(work.id)).isEmpty()
        assertThat(released).containsExactly(41)

        main.failWrites = null
        assertThat(store.current()).isEqualTo(1L)
        assertThat(store.list()).containsExactly(HomeProfile(1, ""), HomeProfile(work.id, "Work")).inOrder()
        assertThat(profiles.switchTo(work.id)).isTrue()
        assertThat(profiles.rename(work.id, "Job")).isTrue()
        assertThat(profiles.switchTo(1)).isTrue()
        // With room again, deleting it once more finishes the work.
        assertThat(profiles.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)
        assertThat(store.list().map { it.id }).containsExactly(1L)
    }

    /**
     * Review round 13. A profile whose own store cannot be read is not switched to: the switch says it failed
     * and the profile that was on stays on. The switch wrote only which profile is on, with no look at the
     * store the settings are read from after it. From that write on every reading of the settings failed, at
     * the next start too: the home screen did not come up, and Settings, where the switch could be taken
     * back, did not either.
     */
    @Test fun aProfileWhoseOwnStoreCannotBeReadIsNotSwitchedTo() = runTest {
        val work = created("Work", copyCurrent = false)
        files.store(work.id).failReads = java.io.IOException("Input/output error")

        assertThat(profiles.switchTo(work.id)).isFalse()

        assertThat(store.current()).isEqualTo(1L)
        assertThat(settings.settings.first().homeProfileId).isEqualTo(1L)
        // Deleting the profile that is on begins by switching to the next one, and that is the same switch.
        assertThat(profiles.delete(1)).isEqualTo(HomeProfileRepository.Deleted.Failed)
        assertThat(store.current()).isEqualTo(1L)
        assertThat(store.list().map { it.id }).containsExactly(1L, work.id).inOrder()

        // Read again, it is switched to as any other.
        files.store(work.id).failReads = null
        assertThat(profiles.switchTo(work.id)).isTrue()
        assertThat(settings.settings.first().homeProfileId).isEqualTo(work.id)
    }

    /**
     * Review round 13, second run. A deletion removed the profile's picture folder and did not look at whether
     * it went: with a picture that could not be removed it took the profile out of the list and said it was
     * gone, and the picture stayed on the disk under an id nothing names any more. The profile is now left
     * listed, with its home screen gone, as when the list cannot be written: deleting it again finishes the
     * work, and that is what Settings tells the user.
     */
    @Test fun aProfileWhosePictureCannotBeRemovedIsLeftListedToBeDeletedAgain() = runTest {
        var removable = false
        // A folder that is not there is removed; one that is there goes only when the disk lets it.
        val pictures = DrawerWallpaperStore(
            context, Dispatchers.IO, remove = { if (removable) it.deleteRecursively() else !it.exists() },
        )
        val repository = HomeProfileRepository(store, layout, settings, resolver, pictures) { released += it }
        val work = (repository.create("Work", copyOf = null) as HomeProfileRepository.Created.Done).profile
        pictures.set(picture(Color.RED), work.id)
        inHomeProfile(work.id) { dao.insert(app("com.chat", 0)) }

        assertThat(repository.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.LeftEmpty)

        assertThat(store.list().map { it.id }).containsExactly(1L, work.id).inOrder()
        assertThat(pictures.profilesWithPictures()).contains(work.id)
        assertThat(rowsOf(work.id)).isEmpty()

        removable = true
        assertThat(repository.delete(work.id)).isEqualTo(HomeProfileRepository.Deleted.Done)
        assertThat(store.list().map { it.id }).containsExactly(1L)
        assertThat(pictures.profilesWithPictures()).doesNotContain(work.id)
    }

    /**
     * The same look where a new profile is made. An id is given again while only an attempt that died was
     * ever kept under it, and its maker begins by clearing what that attempt left. A picture left there that
     * could not be removed was passed over: the new profile was made over it and showed, behind its drawer,
     * a picture nobody had chosen for it. No profile is made then, and that is said.
     */
    @Test fun noProfileIsMadeOverAPictureAnEarlierAttemptLeftThatCannotBeCleared() = runTest {
        val pictures = DrawerWallpaperStore(context, Dispatchers.IO, remove = { !it.exists() })
        val repository = HomeProfileRepository(store, layout, settings, resolver, pictures) { released += it }
        // What an attempt that died left under the id the next profile gets: its picture, and a store that
        // says it was being made.
        pictures.set(picture(Color.RED), 2)
        files.store(2).updateData {
            it.toMutablePreferences().apply { this[booleanPreferencesKey(HomeProfileKeys.BEING_MADE)] = true }.toPreferences()
        }

        assertThat(repository.create("Work", copyOf = null)).isEqualTo(HomeProfileRepository.Created.Failed)

        assertThat(store.list().map { it.id }).containsExactly(1L)
    }

    /**
     * Review round 13. Where no profile id is left (the list holds the last number there is, which only a
     * settings file that was not written here can), a profile is not made and Settings is told so. One more
     * than that number is a negative id: the profile was reported as made, and the list never showed it.
     */
    @Test fun aProfileIsNotMadeWhenNoIdIsLeftAndThatIsSaid() = runTest {
        main.updateData {
            it.toMutablePreferences().apply {
                this[stringPreferencesKey(HomeProfileKeys.LIST)] = "1\t\n${Long.MAX_VALUE}\tWork"
            }.toPreferences()
        }

        assertThat(profiles.create("Next", copyOf = null)).isEqualTo(HomeProfileRepository.Created.Failed)

        assertThat(store.list().map { it.id }).containsExactly(1L, Long.MAX_VALUE).inOrder()
    }

    /**
     * Review round 5. The first thing written of a new profile is its own values, and that write can fail as
     * any other (a full disk). It is a profile that could not be made, said as one ([Created.Failed]): the
     * failure used to be thrown to Settings, which asks without catching, and took the launcher down.
     */
    @Test fun aProfileWhoseOwnValuesCannotBeWrittenIsNotMade() = runTest {
        // The disk is full: the file the next profile's own values go to cannot be written.
        fun noSpace() {
            files.store(2L).failWrites = java.io.IOException("No space left on device")
        }

        noSpace()
        assertThat(profiles.create("Work", copyOf = 1)).isEqualTo(HomeProfileRepository.Created.Failed)
        // Nothing is left of the attempt, and the list is as it was.
        assertThat(files.stores.keys).doesNotContain(2L)
        assertThat(store.list().map { it.id }).containsExactly(1L)

        noSpace()
        assertThat(profiles.create("Evening", copyOf = null)).isEqualTo(HomeProfileRepository.Created.Failed)
        assertThat(files.stores.keys).doesNotContain(2L)
        assertThat(store.list().map { it.id }).containsExactly(1L)
        // With room again the next profile is made, under the same id.
        assertThat(created("Work", copyCurrent = true).id).isEqualTo(2L)
    }

    /** Review round 3: what a screen asks of the layout it shows runs only while that profile is the one that
     *  is on, and the profile stays on for as long as it runs. */
    @Test fun whatAScreenAsksOfItsProfileRunsOnlyWhileThatProfileIsOn() = runTest {
        val work = created("Work", copyCurrent = false)
        var ran = 0

        assertThat(layout.whileProfileIsOn(work.id) { ran++ }).isNull()
        assertThat(ran).isEqualTo(0)
        assertThat(layout.isProfileOn(work.id)).isFalse()

        assertThat(layout.whileProfileIsOn(1L) { ran++; "done" }).isEqualTo("done")
        assertThat(ran).isEqualTo(1)
        assertThat(layout.isProfileOn(1L)).isTrue()
    }

    @Test fun unreadableProfileLeavesTheGridColumnsUntouched() = runTest {
        assertGridReadFails { profiles.setHomeColumns(3, 1L) }
    }

    @Test fun unreadableProfileLeavesTheGridRowsUntouched() = runTest {
        assertGridReadFails { profiles.setHomeRows(5, 1L) }
    }

    private suspend fun assertGridReadFails(change: suspend () -> Boolean) {
        dao.insert(app("com.edge", 3))
        val before = settings.settings.first()
        val rows = rowsOf(1)
        main.failReads = java.io.IOException("cannot open profile")
        assertThat(change()).isFalse()
        main.failReads = null
        assertThat(settings.settings.first()).isEqualTo(before)
        assertThat(rowsOf(1)).isEqualTo(rows)
    }

    @Test fun unreadableProfilePreventsPickingAPicture() = runTest {
        main.failReads = java.io.IOException("cannot open profile")
        assertThat(profiles.setDrawerPicture(1L) { _, _ -> error("must not start saving") }).isFalse()
    }

    @Test fun unreadableProfileAfterPreparingAPictureKeepsTheOriginal() = runTest {
        assertThat(drawerPictures.set(picture(Color.RED), 1L)).isTrue()
        val old = checkNotNull(drawerPictures.current.value).file
        val result = profiles.setDrawerPicture(1L) { pictures, pick ->
            pictures.set(picture(Color.BLUE), pick).also { main.failReads = java.io.IOException("cannot confirm profile") }
        }
        assertThat(result).isFalse()
        assertThat(old.isFile).isTrue()
        assertThat(drawerPictures.current.value?.file).isEqualTo(old)
        assertThat(old.parentFile!!.listFiles().orEmpty().toList()).containsExactly(old)
    }
}
