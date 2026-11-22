package org.arkikeskus.launcher.data.profile

import com.google.common.truth.Truth.assertThat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.AppUsageRepository
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.HomeFolderIcons
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.HomeProfile
import org.arkikeskus.launcher.model.LauncherSettings
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

    /**
     * Review round 11. The app keys are read at every start of the home screen (an app whose launcher activity
     * was renamed is followed in the settings). They were read through "every profile", which fails when the
     * profiles' stores cannot be listed. That failure is for the reader that DELETES what no profile names; here
     * it escaped at the start, every time the folder could not be read. The keys are read from the profiles that
     * can be told of, which are the ones the correction is then written to; the pictures in use are still not
     * known until the stores can be listed.
     */
    @Test fun theAppKeysAreReadFromTheProfilesThereAreWhileTheStoresCannotBeListed() = runTest {
        settings.setAppHidden("h/h.M/0", true)
        settings.setLeftSwipeAppKey("one/one.M/0")
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setLeftSwipeAppKey("two/two.M/0")
        files.failIds = java.io.IOException("the folder could not be read")

        assertThat(settings.referencedAppKeys()).containsExactly("h/h.M/0", "one/one.M/0", "two/two.M/0")
        settings.renameAppKeys(mapOf("one/one.M/0" to "one/one.New/0"))
        assertThat(settings.referencedAppKeys()).containsExactly("h/h.M/0", "one/one.New/0", "two/two.M/0")

        val pictures = runCatching { settings.folderIconsOfEveryProfile() }
        assertThat(pictures.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
    }

    /**
     * Review round 12. And the same when the stores can be listed but ONE of them cannot be read: a profile that
     * is not on, with a store file that fails. The failure came out of the key reading all the same, at every
     * start of the home screen, though the profile that is on was whole. A correction passes such a profile
     * over, reading and writing, and gets to it at a later start; the pictures in use are still not known
     * until every profile was read.
     */
    @Test fun aProfileWhoseOwnStoreCannotBeReadIsPassedOverByTheCorrection() = runTest {
        settings.setAppHidden("h/h.M/0", true)
        settings.setLeftSwipeAppKey("one/one.M/0")
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setLeftSwipeAppKey("two/two.M/0")
        store.setActive(1)
        files.store(2).failReads = java.io.IOException("the store could not be read")

        assertThat(settings.referencedAppKeys()).containsExactly("h/h.M/0", "one/one.M/0")
        settings.renameAppKeys(mapOf("one/one.M/0" to "one/one.New/0", "two/two.M/0" to "two/two.New/0"))
        assertThat(settings.settings.first().leftSwipeAppKey).isEqualTo("one/one.New/0")
        val pictures = runCatching { settings.folderIconsOfEveryProfile() }
        assertThat(pictures.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)

        // Readable again: its key is found again, to be corrected then.
        files.store(2).failReads = null
        assertThat(settings.referencedAppKeys()).containsExactly("h/h.M/0", "one/one.New/0", "two/two.M/0")
    }

    /**
     * Review round 13, second run: a start with the store of the profile that is on unreadable. The settings'
     * first step is a write and their second the reading, and both went to that store and threw, so no home
     * screen came up and no Settings to choose another profile in. The start now shows the profile whose
     * values the settings file holds, and says so to everything that asks which profile is on. The profile
     * that failed is still listed and is not written to: it cannot be switched to while its store fails, and
     * is as it was when the store reads again.
     */
    @Test fun aStartWithTheStoreOfTheProfileThatIsOnUnreadableShowsTheProfileInTheSettingsFile() = runTest {
        settings.setBaseColour("blue")
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setBaseColour("green")
        val ownWrites = files.store(2).writes
        files.store(2).failReads = java.io.IOException("Input/output error")

        val started = SettingsRepository(view, view)
        val first = started.settings.first()

        assertThat(first.homeProfileId).isEqualTo(HomeProfile.FIRST_ID)
        assertThat(first.baseColour).isEqualTo("blue")
        assertThat(store.current()).isEqualTo(HomeProfile.FIRST_ID)
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L)
        // The settings say which profile was left, for whoever tells the user, until the user was told.
        assertThat(started.leftHomeProfile.first()).isEqualTo(2L)
        started.toldOfLeftHomeProfile(2L)
        assertThat(started.leftHomeProfile.first()).isNull()
        assertThat(runCatching { store.setActive(2) }.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        assertThat(store.current()).isEqualTo(HomeProfile.FIRST_ID)
        assertThat(files.store(2).writes).isEqualTo(ownWrites)

        files.store(2).failReads = null
        store.setActive(2)
        assertThat(started.settings.first().baseColour).isEqualTo("green")
    }

    /** Review round 14: the same start with a settings file that reads and cannot be written (a full disk).
     *  Leaving the profile is a write to that file, and its failure was thrown in place of the reading's. */
    @Test fun aStartWhoseSettingsFileCannotBeWrittenStillShowsTheProfileThatCanBeRead() = runTest {
        settings.setBaseColour("blue")
        store.create("Work", copyOf = null)
        store.setActive(2)
        settings.setBaseColour("green")
        files.store(2).failReads = java.io.IOException("Input/output error")
        main.failWrites = java.io.IOException("No space left on device")

        val started = SettingsRepository(view, view)
        val first = started.settings.first()

        assertThat(first.homeProfileId).isEqualTo(HomeProfile.FIRST_ID)
        assertThat(first.baseColour).isEqualTo("blue")
        assertThat(store.current()).isEqualTo(HomeProfile.FIRST_ID)
        assertThat(started.leftHomeProfile.first()).isEqualTo(2L)
    }

    /**
     * Review round 12. The released 1.2.1 kept a key it did not know in the type its backup file gave it, so a
     * settings file that comes to this version from it can hold anything under the profile bookkeeping's
     * names (a hand-edited backup restored there). A text where a number is kept threw at the first reading of
     * the profile that is on, which is the first thing the home screen does; a number that names no profile
     * showed an empty home screen in place of the user's own. What is not of its type, or names no profile of
     * the list, is read as not there: the first profile is on, with the values the settings file holds.
     */
    @Test fun profileBookkeepingOfAnotherTypeOrNamingNoProfileIsReadAsNotThere() = runTest {
        settings.setBaseColour("blue")
        main.edit {
            it[stringPreferencesKey(HomeProfileKeys.ACTIVE)] = "2"
            it[stringPreferencesKey(HomeProfileKeys.MAIN_STORE)] = "2"
            it[longPreferencesKey(HomeProfileKeys.LIST)] = 5L
            it[stringPreferencesKey(HomeProfileKeys.LAST_ID)] = "many"
        }

        assertThat(settings.settings.first().homeProfileId).isEqualTo(HomeProfile.FIRST_ID)
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
        assertThat(store.list().map { it.id }).containsExactly(HomeProfile.FIRST_ID)

        // Numbers that name no profile of the list.
        main.edit {
            it[longPreferencesKey(HomeProfileKeys.ACTIVE)] = 7L
            it[longPreferencesKey(HomeProfileKeys.MAIN_STORE)] = 7L
        }
        assertThat(settings.settings.first().homeProfileId).isEqualTo(HomeProfile.FIRST_ID)
        assertThat(store.current()).isEqualTo(HomeProfile.FIRST_ID)
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")

        // And the bookkeeping goes on from there: a new profile is the second one, and can be switched to.
        store.create("Work", copyOf = null)
        store.setActive(2)
        assertThat(settings.settings.first().homeProfileId).isEqualTo(2L)
        assertThat(settings.settings.first().baseColour).isEqualTo("orange")
        store.setActive(1)
        assertThat(settings.settings.first().baseColour).isEqualTo("blue")
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

    /**
     * Review round 13. The settings file a user upgrades from 1.2.1 with can hold a setting this version added
     * in a type that is not its own: 1.2.1 kept a key it did not know in the type its backup file gave it. A
     * typed key hands such a value over as it is, and the home screen's first reading of the settings threw
     * on it, at every start. It is read as not set, by every reader of the settings file and whichever of
     * them reads first; only numbers were set right before, and only for the reader that read all settings.
     */
    @Test fun aSettingKeptInAnotherTypeIsReadAsNotSetByEveryReader() = runTest {
        main.updateData {
            it.toMutablePreferences().apply {
                this[stringPreferencesKey("search_hidden_apps")] = "yes"
                this[stringPreferencesKey("drawer_search_at_bottom")] = "yes"
                this[longPreferencesKey("count_system_usage")] = 1L
                this[booleanPreferencesKey("weather_icon_size")] = true
                this[booleanPreferencesKey("swipe_down_apps")] = true
                this[stringPreferencesKey("app_usage_system_synced_to")] = "soon"
                this[stringPreferencesKey("base_colour")] = "blue"
            }.toPreferences()
        }

        // Readers that are not "all the settings", and read before it.
        assertThat(AppUsageRepository(view).systemSyncedTo.first()).isEqualTo(0L)
        assertThat(settings.swipeDownApps.first()).isEmpty()
        val read = settings.settings.first()
        assertThat(read.searchHiddenApps).isFalse()
        assertThat(read.drawerSearchAtBottom).isFalse()
        assertThat(read.countSystemUsage).isFalse()
        assertThat(read.weatherIconSize).isEqualTo(LauncherSettings.WEATHER_SIZE_STANDARD)
        // What is in its own type is read as ever.
        assertThat(read.baseColour).isEqualTo("blue")

        // And the next change of any setting leaves the file itself without them.
        settings.setLongPressScale(1.5f)
        assertThat(main.now.asMap().keys.map { it.name })
            .containsExactly("base_colour", "long_press_scale")
    }

    /** Review round 13. A profile's own store is read in the settings' types as the settings file is: while
     *  the profile is on, and when the start reads every profile's values for the apps they name. */
    @Test fun aProfilesOwnStoreIsReadInTheSettingsTypesToo() = runTest {
        store.create("Work", copyOf = null)
        files.store(2).updateData {
            it.toMutablePreferences().apply {
                this[booleanPreferencesKey("drawer_folders")] = true
                this[stringPreferencesKey("drawer_search_at_bottom")] = "yes"
                this[stringPreferencesKey("left_swipe_app_key")] = "com.mail/Main/0"
            }.toPreferences()
        }

        assertThat(settings.referencedAppKeys()).containsExactly("com.mail/Main/0")
        assertThat(view.profileValues(2).asMap().keys.map { it.name }).containsExactly("left_swipe_app_key")

        store.setActive(2)
        assertThat(settings.settings.first().drawerSearchAtBottom).isFalse()
        assertThat(settings.drawerFolders.first()).isEmpty()
        assertThat(settings.settings.first().leftSwipeAppKey).isEqualTo("com.mail/Main/0")
    }
}
