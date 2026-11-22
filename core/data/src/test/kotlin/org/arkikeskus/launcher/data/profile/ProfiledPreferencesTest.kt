package org.arkikeskus.launcher.data.profile

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The one settings store the launcher reads is a view: what every home profile shares comes from the
 * settings file, what a profile has of its own comes from that profile. The profile whose own values
 * are in the settings file itself (the first one, until it is deleted) needs no other file, so a
 * launcher with one profile reads and writes exactly the file it always has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfiledPreferencesTest {
    private val main = CountingStore()
    private val files = FakeHomeProfileFiles()
    private val view = ProfiledPreferences(main, files)

    private val active = longPreferencesKey(HomeProfileKeys.ACTIVE)
    private val mainStore = longPreferencesKey(HomeProfileKeys.MAIN_STORE)
    private val list = stringPreferencesKey(HomeProfileKeys.LIST)
    private val baseColour = stringPreferencesKey("base_colour")
    private val homeColumns = intPreferencesKey("home_columns")
    private val longPress = floatPreferencesKey("long_press_scale")

    private fun Preferences.names(): Set<String> = asMap().keys.map { it.name }.toSet()

    /** The profiles there are in these tests. The view follows a pointer (the profile that is on, the one the
     *  settings file holds) only to a profile the list names: a pointer to none is read as the first profile. */
    @Before fun threeProfiles() = runBlocking<Unit> { main.edit { it[list] = "1\t\n2\tWork\n3\tPlay" } }

    @Test fun withOneProfileTheViewIsTheExistingFile() = runTest {
        view.edit { it[homeColumns] = 5; it[longPress] = 1.5f }

        assertThat(main.now[homeColumns]).isEqualTo(5)
        assertThat(main.now[longPress]).isEqualTo(1.5f)
        assertThat(view.data.first()).isEqualTo(main.now)
        assertThat(files.stores).isEmpty()
    }

    @Test fun anotherProfileReadsItsOwnLookAndTheSharedBehaviour() = runTest {
        main.edit { it[baseColour] = "blue"; it[longPress] = 1.5f; it[active] = 2L }
        files.store(2).edit { it[baseColour] = "green" }

        val seen = view.data.first()

        assertThat(seen[baseColour]).isEqualTo("green")
        assertThat(seen[longPress]).isEqualTo(1.5f)
        assertThat(seen[active]).isEqualTo(2L)
    }

    @Test fun whatTheFirstProfileKeepsInTheSettingsFileDoesNotShowInAnother() = runTest {
        main.edit { it[baseColour] = "blue"; it[homeColumns] = 6; it[active] = 2L }

        val seen = view.data.first()

        // Profile 2 has set neither: it gets the defaults, not profile 1's choices.
        assertThat(seen[baseColour]).isNull()
        assertThat(seen[homeColumns]).isNull()
    }

    @Test fun aChangeThatCannotBeSavedInTheProfileIsNotSavedInTheSharedPartEither() = runTest {
        main.edit { it[longPress] = 1f; it[active] = 2L }
        files.store(2).edit { it[baseColour] = "green" }
        val before = main.now
        files.store(2).failWrites = IOException("no space left")

        val failure = runCatching { view.edit { it[longPress] = 1.5f; it[baseColour] = "blue" } }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        // Half of one change is no change: the press delay did not move for every profile while the look stayed.
        assertThat(main.now).isEqualTo(before)
        assertThat(files.store(2).now[baseColour]).isEqualTo("green")
    }

    @Test fun aPerProfileEditGoesToTheActiveProfileOnly() = runTest {
        main.edit { it[baseColour] = "blue"; it[active] = 2L }

        view.edit { it[baseColour] = "green" }

        assertThat(main.now[baseColour]).isEqualTo("blue")
        assertThat(files.store(2).now[baseColour]).isEqualTo("green")
        assertThat(view.data.first()[baseColour]).isEqualTo("green")
    }

    @Test fun aSharedEditIsSeenFromEveryProfile() = runTest {
        main.edit { it[active] = 2L }

        view.edit { it[longPress] = 0.5f }

        assertThat(main.now[longPress]).isEqualTo(0.5f)
        assertThat(files.store(2).now.names()).isEmpty()
        main.edit { it[active] = 1L }
        assertThat(view.data.first()[longPress]).isEqualTo(0.5f)
    }

    @Test fun clearingTheViewUnderAnotherProfileKeepsTheFirstProfilesValues() = runTest {
        main.edit { it[baseColour] = "blue"; it[homeColumns] = 6; it[longPress] = 1.5f; it[active] = 2L; it[list] = "1\t\n2\tWork" }
        files.store(2).edit { it[baseColour] = "green"; it[homeColumns] = 3 }

        // What a restore does: everything out, then what the backup has.
        view.edit { it.clear(); it[baseColour] = "red" }

        // Profile 1's own values are untouched, the shared one is gone, the bookkeeping stays.
        assertThat(main.now[baseColour]).isEqualTo("blue")
        assertThat(main.now[homeColumns]).isEqualTo(6)
        assertThat(main.now[longPress]).isNull()
        assertThat(main.now[active]).isEqualTo(2L)
        assertThat(main.now[list]).isEqualTo("1\t\n2\tWork")
        // Profile 2 holds only what was written after the clear.
        assertThat(files.store(2).now.names()).containsExactly("base_colour")
        assertThat(files.store(2).now[baseColour]).isEqualTo("red")
    }

    @Test fun theReservedKeysCannotBeChangedThroughTheView() = runTest {
        main.edit { it[list] = "1\tHome\n2\tWork" }

        // With one profile on: neither a set nor a clear reaches them.
        view.edit { it[active] = 7L; it[mainStore] = 7L }
        assertThat(main.now[active]).isNull()
        assertThat(main.now[mainStore]).isNull()
        view.edit { it.clear() }
        assertThat(main.now[list]).isEqualTo("1\tHome\n2\tWork")

        // And with another profile on.
        main.edit { it[active] = 2L }
        view.edit { it[active] = 1L; it.remove(list) }
        assertThat(main.now[active]).isEqualTo(2L)
        assertThat(main.now[list]).isEqualTo("1\tHome\n2\tWork")
        assertThat(files.store(2).now.names()).isEmpty()
    }

    @Test fun anEditOfOneKeyWritesOneStore() = runTest {
        main.edit { it[active] = 2L }
        val mainBefore = main.writes
        val ownBefore = files.store(2).writes

        view.edit { it[baseColour] = "green" }
        assertThat(main.writes).isEqualTo(mainBefore)
        assertThat(files.store(2).writes).isEqualTo(ownBefore + 1)

        view.edit { it[longPress] = 2f }
        assertThat(main.writes).isEqualTo(mainBefore + 1)
        assertThat(files.store(2).writes).isEqualTo(ownBefore + 1)
    }

    @Test fun switchingTheActiveIdSwitchesWhatTheViewEmits() = runTest(UnconfinedTestDispatcher()) {
        main.edit { it[baseColour] = "blue"; it[longPress] = 1.5f }
        files.store(2).edit { it[baseColour] = "green" }
        val seen = mutableListOf<Preferences>()
        backgroundScope.launch { view.data.collect { seen += it } }

        main.edit { it[active] = 2L }
        main.edit { it[active] = 1L }

        assertThat(seen.map { it[baseColour] }).containsExactly("blue", "green", "blue").inOrder()
        // Never the look of one profile under the id of another.
        for (p in seen) {
            assertThat(p[baseColour]).isEqualTo(if ((p[active] ?: 1L) == 2L) "green" else "blue")
            assertThat(p[longPress]).isEqualTo(1.5f)
        }
    }

    @Test fun profileValuesAndUpdateProfileReachAProfileThatIsNotActive() = runTest {
        main.edit { it[baseColour] = "blue"; it[longPress] = 1.5f }
        files.store(2).edit { it[baseColour] = "green" }

        // Profile 2 while 1 is on.
        assertThat(view.profileValues(2)[baseColour]).isEqualTo("green")
        assertThat(view.profileValues(2).names()).containsExactly("base_colour")
        view.updateProfile(2) { it[homeColumns] = 3; it[longPress] = 9f }
        assertThat(files.store(2).now[homeColumns]).isEqualTo(3)
        // Only a profile's own values can be written this way.
        assertThat(files.store(2).now[longPress]).isNull()
        assertThat(main.now[longPress]).isEqualTo(1.5f)

        // Profile 1 while 2 is on: its values are the per-profile ones of the settings file.
        main.edit { it[active] = 2L }
        assertThat(view.profileValues(1).names()).containsExactly("base_colour")
        view.updateProfile(1) { it[baseColour] = "red" }
        assertThat(main.now[baseColour]).isEqualTo("red")
        assertThat(view.data.first()[baseColour]).isEqualTo("green")
    }

    @Test fun theProfileInTheSettingsFileNeedNotBeTheFirst() = runTest {
        // After the first profile was deleted, profile 2's values live in the settings file.
        main.edit { it[mainStore] = 2L; it[active] = 2L; it[baseColour] = "green" }
        files.store(3).edit { it[baseColour] = "red" }

        assertThat(view.data.first()[baseColour]).isEqualTo("green")
        view.edit { it[homeColumns] = 5 }
        assertThat(main.now[homeColumns]).isEqualTo(5)

        main.edit { it[active] = 3L }
        assertThat(view.data.first()[baseColour]).isEqualTo("red")
        assertThat(view.profileValues(2)[baseColour]).isEqualTo("green")
    }

    /**
     * Review round 13, second run. The profile that is on kept its own values in a store of its own, and at a
     * later start that store could not be read. Every reader of the settings was sent to it all the same: the
     * home screen's first reading threw, and so did that of Settings, where another profile is chosen. A
     * profile is not switched to unread (round 13); one whose store stops reading while it is on is left the
     * same way. The profile whose values the settings file itself holds comes on: the file that was just read.
     * The profile that failed keeps its place in the list and everything its store holds, with nothing
     * written to it: it is there to switch back to when its store reads again.
     */
    @Test fun whenTheStoreOfTheProfileThatIsOnCannotBeReadTheProfileInTheSettingsFileComesOn() = runTest {
        main.edit { it[baseColour] = "blue"; it[longPress] = 1.5f; it[active] = 2L }
        files.store(2).edit { it[baseColour] = "green" }
        val ownWrites = files.store(2).writes
        files.store(2).failReads = IOException("Input/output error")

        val seen = view.data.first()

        assertThat(seen[baseColour]).isEqualTo("blue")
        assertThat(seen[longPress]).isEqualTo(1.5f)
        assertThat(seen[active]).isEqualTo(1L)
        assertThat(main.now[active]).isEqualTo(1L)
        assertThat(main.now[list]).isEqualTo("1\t\n2\tWork\n3\tPlay")
        assertThat(files.store(2).writes).isEqualTo(ownWrites)
        assertThat(files.store(2).now[baseColour]).isEqualTo("green")
    }

    /** And the profile in the settings file is whichever one the file says it holds, not the first by rule. */
    @Test fun theProfileThatComesOnIsTheOneTheSettingsFileHolds() = runTest {
        main.edit { it[mainStore] = 2L; it[active] = 3L; it[baseColour] = "green" }
        files.store(3).edit { it[baseColour] = "red" }
        files.store(3).failReads = IOException("Input/output error")

        assertThat(view.data.first()[baseColour]).isEqualTo("green")
        assertThat(main.now[active]).isEqualTo(2L)
        assertThat(main.now[mainStore]).isEqualTo(2L)
    }

    /**
     * The same from a write, which is what a start does first (the settings put their values in their own
     * types before they are read): it threw as the reading did. The profile in the settings file comes on, and
     * the change is made as one made for a profile that is not on any more: what every profile shares is
     * written, and no profile's own values are. They were worked out for a profile nobody could read; written
     * to the one that came on, they would have been another profile's look in its place.
     */
    @Test fun aChangeMadeWhileTheStoreOfTheProfileThatIsOnCannotBeReadChangesNoProfilesOwnValues() = runTest {
        main.edit { it[baseColour] = "blue"; it[longPress] = 1.5f; it[active] = 2L }
        files.store(2).edit { it[baseColour] = "green" }
        val ownWrites = files.store(2).writes
        files.store(2).failReads = IOException("Input/output error")

        val result = view.updateData { p -> p.toMutablePreferences().apply { this[baseColour] = "red"; this[longPress] = 0.5f } }

        assertThat(main.now[active]).isEqualTo(1L)
        assertThat(main.now[longPress]).isEqualTo(0.5f)
        assertThat(main.now[baseColour]).isEqualTo("blue")
        assertThat(files.store(2).writes).isEqualTo(ownWrites)
        assertThat(files.store(2).now[baseColour]).isEqualTo("green")
        assertThat(result).isEqualTo(view.data.first())
    }

    /**
     * The owner's word on the round 14 request (2026-11-22): the user is told. The view names the profile that
     * was left until someone has told the user of it, whether a reading or a change found the store failing;
     * it names none while no profile was left, and none again once the user was told.
     */
    @Test fun theProfileThatWasLeftIsNamedUntilTheUserWasToldOfIt() = runTest {
        main.edit { it[baseColour] = "blue"; it[active] = 2L }
        assertThat(view.data.first()[active]).isEqualTo(2L)
        assertThat(view.leftUnread.value).isNull()

        files.store(2).failReads = IOException("Input/output error")
        view.data.first()
        assertThat(view.leftUnread.value).isEqualTo(2L)
        view.toldOfLeftProfile(2L)
        assertThat(view.leftUnread.value).isNull()

        // And from a change, with the third profile on and failing.
        main.edit { it[active] = 3L }
        files.store(3).failReads = IOException("Input/output error")
        view.edit { it[longPress] = 0.5f }
        assertThat(view.leftUnread.value).isEqualTo(3L)
        // Telling of another profile than the one named does not take the name away.
        view.toldOfLeftProfile(2L)
        assertThat(view.leftUnread.value).isEqualTo(3L)
    }

    @Test fun anUpdateReturnsTheNewView() = runTest {
        main.edit { it[longPress] = 1.5f; it[active] = 2L }

        val result = view.updateData { p -> p.toMutablePreferences().apply { this[baseColour] = "green" } }

        assertThat(result[baseColour]).isEqualTo("green")
        assertThat(result[longPress]).isEqualTo(1.5f)
        assertThat(result[active]).isEqualTo(2L)
        assertThat(result).isEqualTo(view.data.first())
    }
}
