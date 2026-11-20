package org.arkikeskus.launcher.data.profile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
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

    private suspend fun created(name: String, copyCurrent: Boolean): HomeProfile =
        (profiles.create(name, copyCurrent) as HomeProfileRepository.Created.Done).profile

    private fun picture(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

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

        assertThat(profiles.create("One too many", copyCurrent = true)).isEqualTo(HomeProfileRepository.Created.Full)
        assertThat(profiles.profiles.first()).hasSize(HomeProfile.MAX_PROFILES)
        assertThat(dao.profileIds()).isEmpty()
    }

    @Test fun deletingTheActiveProfileLandsOnItsNeighbour() = runTest {
        created("Work", copyCurrent = false)
        created("Evening", copyCurrent = false)
        profiles.switchTo(2)

        assertThat(profiles.delete(2)).isTrue()

        // The one after it; had it been the last, the one before.
        assertThat(store.current()).isEqualTo(3L)
        assertThat(store.list().map { it.id }).containsExactly(1L, 3L).inOrder()
        assertThat(profiles.delete(3)).isTrue()
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

        assertThat(profiles.delete(work.id)).isTrue()

        assertThat(rowsOf(work.id)).isEmpty()
        assertThat(rowsOf(1)).hasSize(1)
        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
        // The system holds what the profiles that are left still have.
        assertThat(resolver.pins[("com.chat" to 0L)]).containsExactly("home-only")
        assertThat(resolver.pins[("com.maps" to 0L)]).isEmpty()
    }

    @Test fun theLastProfileStays() = runTest {
        dao.insert(app("com.mail", 0))

        assertThat(profiles.delete(1)).isFalse()
        assertThat(profiles.delete(7)).isFalse()

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
        caller = launch { profiles.create("Work", copyCurrent = true) }
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

        assertThat(repositoryOver(tapped).delete(work.id)).isTrue()

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

        repositoryOver(watching).create("Work", copyCurrent = true)

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

        repositoryOver(tapped).setHomeColumns(4)

        // The profile that was on: four columns, and its icon inside them.
        assertThat(view.profileValues(1)[columns]).isEqualTo(4)
        assertThat(rowsOf(1).single().cellX).isLessThan(4)
        // The other one keeps its grid and its icon in the fifth column.
        assertThat(view.profileValues(work.id)[columns]).isEqualTo(5)
        assertThat(rowsOf(work.id).single().cellX).isEqualTo(4)
    }

    @Test fun deletingAProfileFreesTheWidgetIdsItHeld() = runTest {
        dao.insert(widget("com.clock", 11, cellX = 0))
        val work = created("Work", copyCurrent = false)
        inHomeProfile(work.id) {
            dao.insert(widget("com.cal", 12, cellX = 0))
            dao.insert(widget("com.news", 13, cellX = 2))
        }

        assertThat(profiles.delete(work.id)).isTrue()

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

        assertThat(profiles.delete(work.id)).isTrue()

        assertThat(released).containsExactly(12, 13)
        assertThat(releaseThreads).doesNotContain(caller)
    }

    @Test fun theSystemsPinnedSetsAreWrittenOffTheCallersThread() = runTest {
        dao.insert(shortcut("com.chat", "home-only", cellX = 1))
        val work = created("Work", copyCurrent = true)
        val caller = Thread.currentThread()

        assertThat(profiles.delete(work.id)).isTrue()

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
        assertThat(profiles.delete(copy.id)).isTrue()

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

        assertThat(repositoryOver(blocking).create("Work", copyCurrent = true)).isEqualTo(HomeProfileRepository.Created.Failed)

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
    }

    /** While its rows are there and its picture is still being copied, the copy is not in the list: a process
     *  that dies now leaves no profile with its source's look over half a home screen. */
    @Test fun aCopyIsNotListedBeforeItIsWhole() = runBlocking<Unit> {
        val io = OneAtATime()
        val paced = DrawerWallpaperStore(context, io)
        val repository = HomeProfileRepository(store, layout, settings, resolver, paced) {}
        dao.insert(app("com.mail", 0))

        val making = async(Dispatchers.Default) { repository.create("Work", copyCurrent = true) }
        val listedWhileItsPictureWasStillToCome = try {
            // The picture store's first piece of work: clearing what an earlier attempt left under the id.
            withTimeout(5_000) { while (!io.runOne()) delay(5) }
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

    /** An attempt that died half-way left rows and a picture under the id the next profile gets (an id is given
     *  again until a profile is listed with it): the next profile starts without them, a copy or an empty one. */
    @Test fun whatAnAttemptThatDiedLeftUnderAnIdIsNotInTheNextProfileOfThatId() = runTest {
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
        val whileSaving = profiles.setDrawerPicture(work.id) { store ->
            assertThat(profiles.delete(work.id)).isTrue()
            saves++
            store.set(picture(Color.WHITE), work.id)
        }
        assertThat(whileSaving).isFalse()
        assertThat(folder.exists()).isFalse()

        // Deleted before the picker answered: the picture is not even read.
        val after = profiles.setDrawerPicture(work.id) { store ->
            saves++
            store.set(picture(Color.WHITE), work.id)
        }
        assertThat(after).isFalse()
        assertThat(saves).isEqualTo(1)
        assertThat(folder.exists()).isFalse()
    }

    @Test fun aPictureForAProfileThatIsThereIsSavedThoughItIsNotTheOneThatIsOn() = runTest {
        val work = created("Work", copyCurrent = false)

        assertThat(profiles.setDrawerPicture(work.id) { it.set(picture(Color.BLACK), work.id) }).isTrue()

        assertThat(drawerPictures.current.value).isNull()
        drawerPictures.switchTo(work.id)
        assertThat(drawerPictures.current.value).isNotNull()
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
}
