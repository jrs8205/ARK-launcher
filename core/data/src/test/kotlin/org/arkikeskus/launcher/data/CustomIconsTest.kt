package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.CustomIcon
import org.junit.Test

/**
 * An app's or a folder's own icon, as in Lawnchair and Nova Launcher: a drawable from an installed
 * icon pack, or an image from the gallery saved on this phone. Kept with the other per-app settings;
 * a pack icon travels in a backup, a gallery image stays on the phone that has the file.
 */
class CustomIconsTest {
    private val pack = CustomIcon.FromPack("com.example.pack", "google_chrome")
    private val photo = CustomIcon.FromFile("icon-1.png")

    @Test
    fun `an icon reference reads back as it was written`() {
        assertThat(CustomIcon.decode(pack.encode())).isEqualTo(pack)
        assertThat(CustomIcon.decode(photo.encode())).isEqualTo(photo)
    }

    @Test
    fun `a broken or unsafe reference is no icon`() {
        assertThat(CustomIcon.decode("")).isNull()
        assertThat(CustomIcon.decode("pack:")).isNull()
        assertThat(CustomIcon.decode("pack:com.example.pack")).isNull()
        assertThat(CustomIcon.decode("pack::name")).isNull()
        assertThat(CustomIcon.decode("file:")).isNull()
        assertThat(CustomIcon.decode("file:../settings.xml")).isNull()
        assertThat(CustomIcon.decode("file:a/b.png")).isNull()
        assertThat(CustomIcon.decode("http://example.com/x.png")).isNull()
    }

    @Test
    fun `an app's icon is set and cleared`() = runTest {
        val repo = SettingsRepository(InMemoryDataStore())

        repo.setCustomIcon("com.chrome/Main/0", pack)
        repo.setCustomIcon("com.maps/Main/0", photo)
        assertThat(repo.customIcons.first()).containsExactly("com.chrome/Main/0", pack, "com.maps/Main/0", photo)

        repo.setCustomIcon("com.chrome/Main/0", null)
        assertThat(repo.customIcons.first()).containsExactly("com.maps/Main/0", photo)
    }

    @Test
    fun `a folder's icon is set and cleared`() = runTest {
        val repo = SettingsRepository(InMemoryDataStore())

        repo.setFolderIcon(FolderIconKey.home(7), pack)
        repo.setFolderIcon(FolderIconKey.drawer(2), photo)
        assertThat(repo.folderIcons.first()).containsExactly("home:7", pack, "drawer:2", photo)

        repo.setFolderIcon(FolderIconKey.home(7), null)
        assertThat(repo.folderIcons.first()).containsExactly("drawer:2", photo)
    }

    /** A backup carries pack icons; a gallery image is a file on this phone, so it stays here, and a
     *  restore keeps this phone's images for apps and folders the backup sets no icon for. */
    @Test
    fun `a backup carries pack icons and a restore keeps this phone's images`() = runTest {
        val phone = SettingsRepository(InMemoryDataStore())
        phone.setCustomIcon("com.chrome/Main/0", pack)
        phone.setCustomIcon("com.maps/Main/0", photo)
        phone.setFolderIcon(FolderIconKey.home(7), photo)
        phone.setFolderIcon(FolderIconKey.drawer(2), pack)

        val backup = phone.exportRaw()
        assertThat(backup["custom_icons"]).isEqualTo("com.chrome/Main/0\t${pack.encode()}")
        assertThat(backup["folder_icons"]).isEqualTo("drawer:2\t${pack.encode()}")

        val other = CustomIcon.FromPack("com.example.pack", "maps")
        phone.importRaw(mapOf("custom_icons" to "com.chrome/Main/0\t${other.encode()}"))
        assertThat(phone.customIcons.first()).containsExactly("com.chrome/Main/0", other, "com.maps/Main/0", photo)
        assertThat(phone.folderIcons.first()).containsExactly("home:7", photo)
    }

    @Test
    fun `an app's icon follows its renamed launcher activity`() = runTest {
        val repo = SettingsRepository(InMemoryDataStore())
        repo.setCustomIcon("a/a.Old/0", pack)

        repo.renameAppKeys(mapOf("a/a.Old/0" to "a/a.New/0"))

        assertThat(repo.customIcons.first()).containsExactly("a/a.New/0", pack)
        assertThat(repo.referencedAppKeys()).contains("a/a.New/0")
    }
}
