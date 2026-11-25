package org.arkikeskus.launcher.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.CustomIcon
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A gallery image replaced or cleared is deleted from the phone; nothing else uses its file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomIconStoreTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val settings = SettingsRepository(InMemoryDataStore())
    private val source = CustomIconSource(context, IconPackRepository(context))
    private val store = CustomIconStore(settings, source)
    private val pack = CustomIcon.FromPack("com.example.pack", "mail")

    private fun image() = source.files.save(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })

    @Test fun savingTheAppNameAndIconUsesOneWrite() = runTest {
        val disk = org.arkikeskus.launcher.data.profile.CountingStore()
        val repository = SettingsRepository(disk)
        val icons = CustomIconStore(repository, source)
        val key = "com.mail/Main/0"
        val old = image()
        icons.setAppIcon(key, old)
        val before = disk.writes

        icons.setAppIcon(key, pack, label = "  Letters  ")
        assertThat(disk.writes - before).isEqualTo(1)
        assertThat(repository.customLabels.first()[key]).isEqualTo("Letters")
        assertThat(repository.customIcons.first()[key]).isEqualTo(pack)
        assertThat(source.drawable(old)).isNull()

        icons.setAppIcon(key, null)
        assertThat(repository.customLabels.first()[key]).isEqualTo("Letters")
    }

    @Test fun replacingAnAppsImageDeletesTheOldFile() = runTest {
        val first = image()
        store.setAppIcon("com.mail/Main/0", first)

        store.setAppIcon("com.mail/Main/0", pack)

        assertThat(settings.customIcons.first()).containsExactly("com.mail/Main/0", pack)
        assertThat(source.drawable(first)).isNull()
    }

    @Test fun clearingAFoldersImageDeletesItsFile() = runTest {
        val picture = image()
        store.setFolderIcon(FolderIconKey.home(3), picture)

        store.setFolderIcon(FolderIconKey.home(3), null)

        assertThat(settings.folderIcons.first()).isEmpty()
        assertThat(source.drawable(picture)).isNull()
    }

    @Test fun keepingTheSameImageKeepsItsFile() = runTest {
        val picture = image()
        store.setAppIcon("com.mail/Main/0", picture)

        store.setAppIcon("com.mail/Main/0", picture)

        assertThat(source.drawable(picture)).isNotNull()
    }

    /** A drawer folder's id is the largest one + 1, so a deleted folder's id comes back with the
     *  next new folder: its icon must go with it, and its image file too. */
    @Test fun aDeletedDrawerFoldersIconGoesWithIt() = runTest {
        val picture = image()
        val id = settings.createDrawerFolder("Games")
        store.setFolderIcon(FolderIconKey.drawer(id), picture)

        store.deleteDrawerFolder(id)

        assertThat(settings.drawerFolders.first()).isEmpty()
        assertThat(settings.folderIcons.first()).isEmpty()
        assertThat(source.drawable(picture)).isNull()
        assertThat(settings.createDrawerFolder("Work")).isEqualTo(id)
        assertThat(settings.folderIcons.first()).isEmpty()
    }

    /** An icon left under a free id (an older version, a restored backup) never lands on a new folder. */
    @Test fun aNewDrawerFolderStartsWithoutAnIcon() = runTest {
        settings.setFolderIcon(FolderIconKey.drawer(1), pack)

        val id = settings.createDrawerFolder("Games")

        assertThat(id).isEqualTo(1L)
        assertThat(settings.folderIcons.first()).isEmpty()
    }

    /** At start-up, saved pictures no app or folder uses — left by a process death, a restore, a picker
     *  closed mid-import — are deleted; the ones in use stay, and so does one saved since the start (a
     *  dialog may still be holding it). */
    @Test fun theStartUpSweepDeletesOnlyPicturesNothingUses() = runTest {
        val appPicture = image()
        val folderPicture = image()
        val unused = image()
        store.setAppIcon("com.mail/Main/0", appPicture)
        store.setFolderIcon(FolderIconKey.home(3), folderPicture)
        for (old in listOf(appPicture, folderPicture, unused)) {
            File(context.filesDir, "custom_icons/${old.name}").setLastModified(1_000L)
        }
        val fresh = image()

        store.deleteUnusedPictures(savedBefore = 2_000L)

        assertThat(source.drawable(unused)).isNull()
        assertThat(source.drawable(appPicture)).isNotNull()
        assertThat(source.drawable(folderPicture)).isNotNull()
        assertThat(source.drawable(fresh)).isNotNull()
    }

    /**
     * Review round 10. "Unused" is what no place that can name a picture names. The profiles' own stores are such
     * places, and when they cannot be listed (an I/O error) the set of used pictures is not known whole: the
     * sweep then deleted the folder pictures of a profile whose listing was already lost. Nothing is deleted
     * until every place was read, at the start or when a picture is replaced.
     */
    @Test fun nothingIsDeletedWhileTheProfilesStoresCannotBeListed() = runTest {
        val files = org.arkikeskus.launcher.data.profile.FakeHomeProfileFiles()
        val view = org.arkikeskus.launcher.data.profile.ProfiledPreferences(org.arkikeskus.launcher.data.profile.CountingStore(), files)
        val ofProfiles = CustomIconStore(SettingsRepository(view, view), source)
        val first = image()
        val unused = image()
        ofProfiles.setFolderIcon(FolderIconKey.home(3), first)
        for (old in listOf(first, unused)) File(context.filesDir, "custom_icons/${old.name}").setLastModified(1_000L)
        files.failIds = java.io.IOException("the folder could not be read")

        ofProfiles.deleteUnusedPictures(savedBefore = 2_000L)
        // And a picture that is replaced meanwhile is kept too: whether something else still shows it is not known.
        ofProfiles.setFolderIcon(FolderIconKey.home(3), pack)

        assertThat(source.drawable(unused)).isNotNull()
        assertThat(source.drawable(first)).isNotNull()

        // Readable again, the sweep takes both.
        files.failIds = null
        ofProfiles.deleteUnusedPictures(savedBefore = 2_000L)
        assertThat(source.drawable(unused)).isNull()
        assertThat(source.drawable(first)).isNull()
    }

    /** Issue #23: a shortcut's picture counts as used, and clearing it deletes its file like an app's. */
    @Test fun aShortcutsPictureIsKeptBySweepAndDeletedWhenCleared() = runTest {
        val picture = image()
        val key = ShortcutIconKey.of("org.mozilla.firefox", "https://github.com/", 0L)
        store.setShortcutIcon(key, picture)
        File(context.filesDir, "custom_icons/${picture.name}").setLastModified(1_000L)

        store.deleteUnusedPictures(savedBefore = 2_000L)
        assertThat(source.drawable(picture)).isNotNull()

        store.setShortcutIcon(key, null)
        assertThat(source.drawable(picture)).isNull()
        assertThat(settings.shortcutIcons.first()).isEmpty()
    }

    /** An image imported in the picker and then left unused (the dialog cancelled) is deleted. */
    @Test fun anImportedImageLeftUnusedIsDiscarded() {
        val picture = image()
        val choices = IconChoicesSource(context, IconPackRepository(context), source, CoroutineScope(Dispatchers.Unconfined))

        choices.discard(picture)
        choices.discard(pack)

        assertThat(source.drawable(picture)).isNull()
    }
}
