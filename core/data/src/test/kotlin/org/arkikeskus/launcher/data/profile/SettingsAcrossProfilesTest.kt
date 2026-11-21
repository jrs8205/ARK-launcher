package org.arkikeskus.launcher.data.profile

import com.google.common.truth.Truth.assertThat
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.HomeFolderIcons
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.HomeProfile
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    /**
     * Review round 7, carried through to every setting of a Settings page. The page shows one profile's values,
     * and a change made on it can get its turn after a switch that was waiting. A change made FOR a profile
     * writes a profile's own values only while that profile is on: with another one on they are left out (the
     * other profile keeps its look, and the first is not written behind its back either), and what every
     * profile shares is written all the same.
     */
    @Test fun aChangeMadeForAProfileThatIsNotOnAnyMoreChangesNoProfilesOwnValues() = runTest {
        settings.setBaseColour("blue")
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setBaseColour("green")

        // The second profile is on (its values are in a store of its own); the change was made on the first's page.
        writingForHomeProfile(1) {
            settings.setBaseColour("red")
            settings.setLongPressScale(1.5f)
        }
        assertThat(settings.settings.first().baseColour).isEqualTo("green")
        assertThat(settings.settings.first().longPressScale).isEqualTo(1.5f)
        store.setActive(1)
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")

        // The other way round: the first profile is on (its values are in the settings file itself).
        writingForHomeProfile(2) {
            settings.setBaseColour("violet")
            settings.setLongPressScale(0.5f)
        }
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
        assertThat(settings.settings.first().longPressScale).isEqualTo(0.5f)
        store.setActive(2)
        assertThat(settings.settings.first().baseColour).isEqualTo("green")

        // Made for the profile that is on, it is written, as a change that names no profile is.
        writingForHomeProfile(2) { settings.setBaseColour("teal") }
        assertThat(settings.settings.first().baseColour).isEqualTo("teal")
        settings.setBaseColour("rose")
        assertThat(settings.settings.first().baseColour).isEqualTo("rose")
        store.setActive(1)
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
    }

    /**
     * Review round 9. The pictures that are in use are asked of every profile, and "every profile" was the list.
     * The list is in the settings file; a settings file that was damaged reads as one profile, while the other
     * profiles' own stores are still on the disk and still name their folders' pictures. Those pictures were
     * taken for unused at the next start and deleted. A profile whose store is still there is asked too.
     */
    @Test fun theFolderPicturesOfAProfileTheListLostAreStillInUse() = runTest {
        val home = CustomIcon.FromFile("home.png")
        val work = CustomIcon.FromFile("work.png")
        settings.setFolderIcon(FolderIconKey.drawer(1), home)
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setFolderIcon(FolderIconKey.drawer(1), work)
        store.setActive(1)
        assertThat(settings.folderIconsOfEveryProfile()).containsExactly(home, work)

        main.updateData { androidx.datastore.preferences.core.emptyPreferences() }

        assertThat(store.list().map { it.id }).containsExactly(1L)
        assertThat(settings.folderIconsOfEveryProfile()).contains(work)
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

    // What is read or changed in EVERY profile goes by the list of profiles, one profile at a time: not by
    // "the one that is on, and then the others", which misses a profile when the one that is on changes between.

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theFolderIconsOfEveryProfileAreKnownThoughTheProfileChangesBetweenTheReads() = runTest(UnconfinedTestDispatcher()) {
        val home = CustomIcon.FromFile("home.png")
        val work = CustomIcon.FromFile("work.png")
        settings.setFolderIcon(FolderIconKey.home(4), home)
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setFolderIcon(FolderIconKey.home(9), work)
        store.setActive(1)
        // Asked for beside the reader, as a tap in Settings is: the reading holds the list's lock, and a
        // switch made from inside it (as this hook once did) would wait for itself.
        main.onNextRead = { launch { store.setActive(2) } }

        // A picture missing from this list is deleted as unused.
        assertThat(settings.folderIconsOfEveryProfile()).containsExactly(home, work)
    }

    @Test fun aRenamedAppKeyReachesEveryProfileThoughTheProfileChangesMeanwhile() = runTest {
        val leftSwipe = stringPreferencesKey("left_swipe_app_key")
        settings.setLeftSwipeAppKey("old/old.M/0")
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setLeftSwipeAppKey("old/old.M/0")
        store.setActive(1)
        // The profile changes right after the first write of the repair.
        main.afterWrite = {
            main.afterWrite = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.setActive(2) }
        }

        settings.renameAppKeys(mapOf("old/old.M/0" to "old/new.M/0"))

        assertThat(view.profileValues(1)[leftSwipe]).isEqualTo("old/new.M/0")
        assertThat(view.profileValues(2)[leftSwipe]).isEqualTo("old/new.M/0")
    }

    /**
     * Review round 7. A copy's own values are written before the copy is listed (its rows and pictures come in
     * between). The repair of an app's key that changed read the profiles being made, but wrote only the listed
     * ones: run in that moment, it corrected the source and left the copy with the old key in its swipe action
     * and its drawer folders.
     */
    @Test fun aRenamedAppKeyReachesACopyThatIsBeingMade() = runTest {
        val leftSwipe = stringPreferencesKey("left_swipe_app_key")
        settings.setLeftSwipeAppKey("old/old.M/0")
        val copy = store.prepare("Work", copyOf = 1)!!
        assertThat(store.list().map { it.id }).containsExactly(1L)
        assertThat(view.profileValues(copy.id)[leftSwipe]).isEqualTo("old/old.M/0")

        settings.renameAppKeys(mapOf("old/old.M/0" to "old/new.M/0"))

        assertThat(view.profileValues(1)[leftSwipe]).isEqualTo("old/new.M/0")
        assertThat(view.profileValues(copy.id)[leftSwipe]).isEqualTo("old/new.M/0")
        // Listed with the new key, and nothing is written for a profile that was given up.
        assertThat(store.publish(copy)).isTrue()
        assertThat(view.profileValues(copy.id)[leftSwipe]).isEqualTo("old/new.M/0")
        val givenUp = store.prepare("Play", copyOf = 1)!!
        store.discard(givenUp)
        settings.renameAppKeys(mapOf("old/new.M/0" to "old/newer.M/0"))
        assertThat(view.profileValues(givenUp.id)[leftSwipe]).isNull()
    }

    @Test fun aCopysFolderIconsMoveInThatProfileThoughTheProfileChangesMeanwhile() = runTest {
        val home = CustomIcon.FromFile("home.png")
        val work = CustomIcon.FromFile("work.png")
        settings.setFolderIcon(FolderIconKey.home(4), home)
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setFolderIcon(FolderIconKey.home(9), work)
        store.setActive(1)
        // Another profile is tapped as the move begins. (Asked for beside the reader, not inside its read:
        // a switch needs the lock the move holds, so it happens before the move or after it.)
        main.onNextRead = { backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.setActive(2) } }

        settings.moveHomeFolderIcons(profileId = 1, newIds = mapOf(4L to 40L))

        assertThat(settings.folderIcons.first()).containsExactly(FolderIconKey.home(9), work)
        store.setActive(1)
        assertThat(settings.folderIcons.first()).containsExactly(FolderIconKey.home(40), home)
    }

    /**
     * Review round 2. Deleting the profile whose values the settings file holds moves a surviving profile's
     * values INTO that file and drops the survivor's own store. A reader that had just looked up where the
     * survivor's values are (its own store) then read that store after it was gone, got nothing, and the
     * picture clean-up deleted a picture the survivor's folder still shows. The list and every profile's
     * values are read under the lock the delete holds. The delete is asked for as each of the first readers
     * in turn is handed the settings file; without the lock it is the third one that loses the icons.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aSurvivingProfilesFolderIconsAreKnownWhileTheProfileBesideItIsDeleted() = runTest(UnconfinedTestDispatcher()) {
        val work = CustomIcon.FromFile("work.png")
        for (nth in 1..4) {
            val main = CountingStore()
            val files = FakeHomeProfileFiles()
            val view = ProfiledPreferences(main, files)
            val store = HomeProfileStore(main, view, files)
            val settings = SettingsRepository(view, view)
            store.create("Work", copyOf = null)
            store.setActive(2)
            settings.setFolderIcon(FolderIconKey.home(9), work)
            var reads = 0
            fun arm() {
                main.onNextRead = { if (++reads == nth) launch { store.delete(1) } else arm() }
            }
            arm()

            assertWithMessage("the first profile deleted at read %s of the settings file", nth)
                .that(settings.folderIconsOfEveryProfile()).contains(work)
            main.onNextRead = null
        }
    }
}
