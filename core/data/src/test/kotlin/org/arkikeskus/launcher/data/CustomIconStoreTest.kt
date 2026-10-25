package org.arkikeskus.launcher.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.CustomIcon
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    /** An image imported in the picker and then left unused (the dialog cancelled) is deleted. */
    @Test fun anImportedImageLeftUnusedIsDiscarded() {
        val picture = image()
        val choices = IconChoicesSource(context, IconPackRepository(context), source)

        choices.discard(picture)
        choices.discard(pack)

        assertThat(source.drawable(picture)).isNull()
    }
}
