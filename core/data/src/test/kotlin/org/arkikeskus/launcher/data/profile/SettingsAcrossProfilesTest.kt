package org.arkikeskus.launcher.data.profile

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.HomeFolderIcons
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.HomeProfile
import org.junit.Test

/**
 * The settings with several home profiles. What a backup carries is the settings of the profile that is
 * on, never the profile bookkeeping; and what is true of the phone, not of a profile, reaches the settings
 * of every profile: an app whose launcher activity was renamed, a picture that is still in use.
 */
class SettingsAcrossProfilesTest {
    private val main = CountingStore()
    private val files = FakeHomeProfileFiles()
    private val view = ProfiledPreferences(main, files)
    private val store = HomeProfileStore(main, view, files)
    private val settings = SettingsRepository(view, view)

    @Test fun theSettingsNameTheirProfile() = runTest {
        assertThat(settings.settings.first().homeProfileId).isEqualTo(HomeProfile.FIRST_ID)

        store.create("Work", copyOf = null)
        store.setActive(2)

        assertThat(settings.settings.first().homeProfileId).isEqualTo(2L)
        assertThat(settings.homeProfileId.first()).isEqualTo(2L)
    }

    @Test fun eachProfileHasItsOwnLookAndTheSameBehaviour() = runTest {
        settings.setBaseColour("blue")
        settings.setLongPressScale(1.5f)
        store.create("Work", copyOf = null)
        store.setActive(2)

        assertThat(settings.settings.first().baseColour).isEqualTo("orange")
        assertThat(settings.settings.first().longPressScale).isEqualTo(1.5f)
        settings.setBaseColour("green")
        settings.setLongPressScale(0.5f)

        store.setActive(1)
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
        assertThat(settings.settings.first().longPressScale).isEqualTo(0.5f)
    }

    @Test fun anExportHasNoneOfTheReservedKeys() = runTest {
        settings.setBaseColour("blue")
        store.rename(1, "Home")
        store.create("Work", copyOf = 1)
        store.setActive(2)
        settings.setBaseColour("green")

        val exported = settings.exportRaw()

        assertThat(exported.keys).containsNoneIn(HomeProfileKeys.RESERVED)
        // What it does carry is the profile that is on, with the shared settings.
        assertThat(exported["base_colour"]).isEqualTo("green")
    }

    @Test fun anImportCannotSetTheActiveProfile() = runTest {
        store.create("Work", copyOf = null)

        settings.importRaw(
            mapOf(
                "home_profile_active" to 2,
                "home_profile_main_store" to 2,
                "home_profiles" to "9\tGhost",
                "home_profile_last_id" to 40,
                "base_colour" to "green",
            ),
        )

        assertThat(store.current()).isEqualTo(1L)
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(store.create("Evening", null)!!.id).isEqualTo(3L)
        assertThat(settings.settings.first().baseColour).isEqualTo("green")
    }

    @Test fun aRenamedAppKeyIsRewrittenInEveryProfilesDrawerFolders() = runTest {
        val tools = settings.createDrawerFolder("Tools")
        settings.addAppsToDrawerFolder(tools, listOf("a/a.Old/0", "x/x.M/0"))
        settings.setAppHidden("a/a.Old/0", true)
        store.create("Work", copyOf = 1)
        store.setActive(2)
        settings.setLeftSwipeAppKey("a/a.Old/0")
        store.setActive(1)

        // Found while profile 1 is on: the app's launcher activity has a new name.
        settings.renameAppKeys(mapOf("a/a.Old/0" to "a/a.New/0"))

        assertThat(settings.drawerFolders.first().single().appKeys).containsExactly("a/a.New/0", "x/x.M/0").inOrder()
        assertThat(settings.hiddenApps.first()).containsExactly("a/a.New/0")
        store.setActive(2)
        assertThat(settings.drawerFolders.first().single().appKeys).containsExactly("a/a.New/0", "x/x.M/0").inOrder()
        assertThat(settings.settings.first().leftSwipeAppKey).isEqualTo("a/a.New/0")
    }

    @Test fun referencedKeysComeFromEveryProfile() = runTest {
        settings.setAppHidden("h/h.M/0", true)
        settings.setLeftSwipeAppKey("one/one.M/0")
        store.create("Work", copyOf = null)
        store.setActive(2)
        val folder = settings.createDrawerFolder("Work apps")
        settings.addAppsToDrawerFolder(folder, listOf("two/two.M/0"))

        // Whichever profile is on.
        assertThat(settings.referencedAppKeys()).containsExactly("h/h.M/0", "one/one.M/0", "two/two.M/0")
        store.setActive(1)
        assertThat(settings.referencedAppKeys()).containsExactly("h/h.M/0", "one/one.M/0", "two/two.M/0")
    }

    @Test fun theFolderIconsOfEveryProfileAreKnown() = runTest {
        val home = CustomIcon.FromFile("home.png")
        val work = CustomIcon.FromFile("work.png")
        settings.setFolderIcon(FolderIconKey.home(4), home)
        store.create("Work", copyOf = null)
        store.setActive(2)
        // The same key in another profile is another folder.
        settings.setFolderIcon(FolderIconKey.home(4), work)

        assertThat(settings.folderIcons.first().values).containsExactly(work)
        assertThat(settings.folderIconsOfEveryProfile()).containsExactly(home, work)
        store.setActive(1)
        assertThat(settings.folderIconsOfEveryProfile()).containsExactly(home, work)
    }

    @Test fun theFolderIconsSayWhichProfileTheyAreOf() = runTest {
        val home = CustomIcon.FromFile("home.png")
        val work = CustomIcon.FromFile("work.png")
        settings.setFolderIcon(FolderIconKey.home(4), home)
        assertThat(settings.folderIconsOfProfile.first())
            .isEqualTo(HomeFolderIcons(HomeProfile.FIRST_ID, mapOf(FolderIconKey.home(4) to home)))

        store.create("Work", copyOf = null)
        store.setActive(2)
        assertThat(settings.folderIconsOfProfile.first()).isEqualTo(HomeFolderIcons(2, emptyMap()))

        settings.setFolderIcon(FolderIconKey.home(9), work)
        assertThat(settings.folderIconsOfProfile.first()).isEqualTo(HomeFolderIcons(2, mapOf(FolderIconKey.home(9) to work)))
    }

    @Test fun withOneProfileNothingIsDifferent() = runTest {
        val plain = SettingsRepository(org.arkikeskus.launcher.data.InMemoryDataStore())
        plain.setFolderIcon(FolderIconKey.home(4), CustomIcon.FromFile("home.png"))
        plain.setLeftSwipeAppKey("one/one.M/0")

        assertThat(plain.folderIconsOfEveryProfile()).containsExactly(CustomIcon.FromFile("home.png"))
        assertThat(plain.referencedAppKeys()).containsExactly("one/one.M/0")
        assertThat(plain.settings.first().homeProfileId).isEqualTo(HomeProfile.FIRST_ID)
    }
}
