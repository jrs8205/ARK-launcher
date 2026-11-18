package org.arkikeskus.launcher.data.profile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    private lateinit var profiles: HomeProfileRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LauncherDatabase::class.java).allowMainThreadQueries().build()
        dao = db.homeItemDao(store)
        layout = HomeLayoutRepository(db, dao, resolver)
        profiles = HomeProfileRepository(store, layout, settings, resolver, drawerPictures)
    }

    @After fun tearDown() = db.close()

    private fun app(pkg: String, cellX: Int, container: Long = HomeItemEntity.HOME) =
        HomeItemEntity(containerId = container, packageName = pkg, className = "Main", page = 0, cellX = cellX, cellY = 0)

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
        drawerPictures.set(picture(Color.WHITE))
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
}
