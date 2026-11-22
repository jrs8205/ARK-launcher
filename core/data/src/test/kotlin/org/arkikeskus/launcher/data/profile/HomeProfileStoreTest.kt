package org.arkikeskus.launcher.data.profile

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.HomeProfile
import org.arkikeskus.launcher.model.NewAppsRule
import org.junit.Test

/**
 * The list of home profiles and which one is on. A launcher that has stored nothing has one profile, the
 * first, made of the state it always had; a second one starts the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeProfileStoreTest {
    private val main = CountingStore()
    private val files = FakeHomeProfileFiles()
    private val view = ProfiledPreferences(main, files)
    private val store = HomeProfileStore(main, view, files)

    private val mainStore = longPreferencesKey(HomeProfileKeys.MAIN_STORE)
    private val baseColour = stringPreferencesKey("base_colour")
    private val homeColumns = intPreferencesKey("home_columns")
    private val longPress = floatPreferencesKey("long_press_scale")

    private fun Preferences.names(): Set<String> = asMap().keys.map { it.name }.toSet()

    @Test fun withNothingStoredThereIsOneUnnamedProfileAndItIsActive() = runTest {
        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
        assertThat(store.profiles.first()).containsExactly(HomeProfile(1, ""))
        assertThat(store.current()).isEqualTo(1L)
        assertThat(store.id.first()).isEqualTo(1L)
        assertThat(main.writes).isEqualTo(0)
    }

    @Test fun aNewEmptyProfileStartsFromDefaults() = runTest {
        view.edit { it[baseColour] = "blue"; it[longPress] = 1.5f }

        val created = store.create("Work", copyOf = null)

        assertThat(created).isEqualTo(HomeProfile(2, "Work"))
        assertThat(store.list()).containsExactly(HomeProfile(1, ""), HomeProfile(2, "Work")).inOrder()
        assertThat(view.profileValues(2).names()).isEmpty()
        // Making a profile does not switch to it.
        assertThat(store.current()).isEqualTo(1L)
        assertThat(view.data.first()[baseColour]).isEqualTo("blue")
    }

    @Test fun aCopyStartsWithTheSourcesOwnValuesAndNothingElse() = runTest {
        view.edit { it[baseColour] = "blue"; it[homeColumns] = 6; it[longPress] = 1.5f }

        val second = store.create("Copy", copyOf = 1)!!
        // A copy of a profile that lives outside the settings file works the same way.
        view.updateProfile(second.id) { it[baseColour] = "green" }
        val third = store.create("Copy of copy", copyOf = second.id)!!

        assertThat(files.store(second.id).now.names()).containsExactly("base_colour", "home_columns")
        assertThat(view.profileValues(third.id)[baseColour]).isEqualTo("green")
        assertThat(view.profileValues(third.id)[homeColumns]).isEqualTo(6)
        assertThat(files.store(third.id).now.names()).containsExactly("base_colour", "home_columns")
        // The source keeps its own.
        assertThat(view.profileValues(1)[baseColour]).isEqualTo("blue")
    }

    @Test fun aCopyOfAProfileThatDoesNotExistIsEmpty() = runTest {
        val created = store.create("Work", copyOf = 99)!!

        assertThat(view.profileValues(created.id).names()).isEmpty()
    }

    @Test fun theNinthProfileIsRefused() = runTest {
        repeat(HomeProfile.MAX_PROFILES - 1) { assertThat(store.create("P$it", null)).isNotNull() }

        assertThat(store.create("One too many", null)).isNull()
        assertThat(store.list()).hasSize(HomeProfile.MAX_PROFILES)
    }

    @Test fun theLastProfileCannotBeDeleted() = runTest {
        assertThat(store.delete(1)).isFalse()
        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
    }

    @Test fun theActiveProfileCannotBeDeleted() = runTest {
        store.create("Work", null)
        store.setActive(2)

        assertThat(store.delete(2)).isFalse()
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L)
    }

    @Test fun deletingAProfileRemovesItFromTheListWithItsValues() = runTest {
        store.create("Work", null)
        store.create("Evening", null)
        view.updateProfile(3) { it[baseColour] = "red" }

        assertThat(store.delete(3)).isTrue()

        assertThat(store.list().map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(files.deleted).contains(3L)
        // A profile that is not in the list is not deleted twice.
        assertThat(store.delete(3)).isFalse()
    }

    @Test fun deletingTheMainFileProfileMovesAnotherIn() = runTest {
        view.edit { it[baseColour] = "blue"; it[homeColumns] = 6; it[longPress] = 1.5f }
        store.create("Work", null)
        store.setActive(2)
        view.edit { it[baseColour] = "green" }

        assertThat(store.delete(1)).isTrue()

        // What is on screen did not change: profile 2 with its own values and the shared ones.
        val seen = view.data.first()
        assertThat(seen[baseColour]).isEqualTo("green")
        assertThat(seen[homeColumns]).isNull()
        assertThat(seen[longPress]).isEqualTo(1.5f)
        assertThat(store.current()).isEqualTo(2L)
        assertThat(store.list()).containsExactly(HomeProfile(2, "Work"))
        // Profile 2 now lives in the settings file, which holds none of profile 1's own values.
        assertThat(main.now[mainStore]).isEqualTo(2L)
        assertThat(main.now[baseColour]).isEqualTo("green")
        assertThat(main.now[homeColumns]).isNull()
        assertThat(files.stores).doesNotContainKey(2L)
        // And it stays writable there.
        view.edit { it[homeColumns] = 4 }
        assertThat(main.now[homeColumns]).isEqualTo(4)
    }

    @Test fun anIdIsNeverUsedTwice() = runTest {
        assertThat(store.create("Work", null)!!.id).isEqualTo(2L)
        assertThat(store.delete(2)).isTrue()

        assertThat(store.create("Evening", null)!!.id).isEqualTo(3L)
    }

    @Test fun keepOnlyActiveLeavesOneProfileWithItsValues() = runTest {
        view.edit { it[baseColour] = "blue"; it[homeColumns] = 6; it[longPress] = 1.5f }
        store.create("Work", copyOf = 1)
        store.create("Evening", null)
        store.setActive(2)
        view.edit { it[baseColour] = "green" }

        val removed = store.keepOnlyActive()

        assertThat(removed).containsExactly(1L, 3L)
        assertThat(store.list()).containsExactly(HomeProfile(2, "Work"))
        assertThat(store.current()).isEqualTo(2L)
        val seen = view.data.first()
        assertThat(seen[baseColour]).isEqualTo("green")
        assertThat(seen[homeColumns]).isEqualTo(6)
        assertThat(seen[longPress]).isEqualTo(1.5f)
        assertThat(main.now[mainStore]).isEqualTo(2L)
        assertThat(files.stores).isEmpty()
    }

    @Test fun keepOnlyActiveWithTheSettingsFilesProfileOnOnlyDropsTheOthers() = runTest {
        view.edit { it[baseColour] = "blue" }
        store.create("Work", null)

        assertThat(store.keepOnlyActive()).containsExactly(2L)

        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
        assertThat(view.data.first()[baseColour]).isEqualTo("blue")
        assertThat(files.stores).isEmpty()
        // With one profile there is nothing to remove.
        assertThat(store.keepOnlyActive()).isEmpty()
    }

    /** When a profile was last exported is its own, and a copy is a new profile that has never been exported: it
     *  takes its source's look, not its source's bookkeeping. */
    @Test fun aCopyDoesNotTakeItsSourcesLastExportTime() = runTest {
        val lastBackup = longPreferencesKey("local_last_backup_time")
        view.edit { it[baseColour] = "blue"; it[lastBackup] = 77L }

        store.create("Work", copyOf = 1)

        assertThat(view.profileValues(2).names()).containsExactly("base_colour")
        assertThat(view.data.first()[lastBackup]).isEqualTo(77L)
    }

    /** The list is what says which profiles there are: once it is written the others are gone. A store file of
     *  theirs that cannot be removed then is no profile's any more (ids are not reused), and must not turn a
     *  removal that happened into a failure. */
    @Test fun keepOnlyActiveRemovesTheOthersThoughAFileOfTheirsCannotBeDeleted() = runTest {
        val stubborn = object : HomeProfileFiles by files {
            override suspend fun delete(profileId: Long) = throw java.io.IOException("the file is in use")
        }
        val store = HomeProfileStore(main, ProfiledPreferences(main, stubborn), stubborn)
        store.create("Work", null)
        store.create("Evening", null)

        assertThat(store.keepOnlyActive()).containsExactly(2L, 3L)

        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
    }

    @Test fun aRenameIsCutAndCleaned() = runTest {
        store.rename(1, "  My\thome  ")
        assertThat(store.list()).containsExactly(HomeProfile(1, "My home"))

        store.rename(1, "x".repeat(60))
        assertThat(store.list().single().name).hasLength(HomeProfile.MAX_NAME_LENGTH)

        // A profile that is not there is not made by a rename.
        store.rename(9, "Ghost")
        assertThat(store.list()).hasSize(1)
    }

    @Test fun switchingIsSeenByWhoeverFollowsTheActiveProfile() = runTest(UnconfinedTestDispatcher()) {
        store.create("Work", null)
        val seen = mutableListOf<Long>()
        backgroundScope.launch { store.id.collect { seen += it } }

        store.setActive(2)
        store.setActive(2)
        store.setActive(42)
        store.setActive(1)

        assertThat(seen).containsExactly(1L, 2L, 1L).inOrder()
        assertThat(store.current()).isEqualTo(1L)
    }

    @Test fun aProfileMovesInTheListAndStaysInside() = runTest {
        store.create("Work", null)
        store.create("Evening", null)

        store.move(3, by = -1)
        assertThat(store.list().map { it.id }).containsExactly(1L, 3L, 2L).inOrder()

        store.move(3, by = -5)
        assertThat(store.list().map { it.id }).containsExactly(3L, 1L, 2L).inOrder()

        store.move(3, by = 9)
        assertThat(store.list().map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test fun anUpdateChangesTheRuleAndTheScheduleOfAProfileThatExists() = runTest {
        store.create("Work", null)

        store.update(HomeProfile(2, "Work", NewAppsRule.INSTALLED_HERE, startMinutes = 480, weekdays = setOf(1, 2, 3, 4, 5)))
        store.update(HomeProfile(7, "Ghost"))

        assertThat(store.list()).containsExactly(
            HomeProfile(1, ""),
            HomeProfile(2, "Work", NewAppsRule.INSTALLED_HERE, startMinutes = 480, weekdays = setOf(1, 2, 3, 4, 5)),
        ).inOrder()
    }

    @Test fun anEmptyProfileIsEmptyThoughAnInterruptedCopyLeftAFileUnderItsId() = runTest {
        // A copy that died after its values were written and before it was listed: the id is given again.
        main.edit { it[baseColour] = "blue"; it[homeColumns] = 6 }
        assertThat(store.prepare("Copy", copyOf = 1)!!.id).isEqualTo(2L)
        assertThat(view.profileValues(2)[baseColour]).isEqualTo("blue")
        view.made(2)

        val made = store.create("Evening", copyOf = null)!!

        assertThat(made.id).isEqualTo(2L)
        assertThat(view.profileValues(2).asMap()).isEmpty()
    }

    /**
     * Review round 8 (P1). An id that something is kept under is not given, unless its own store says it was an
     * attempt that died: after a damaged settings file the list is one profile again, and the other profiles'
     * values are still in their stores. The new profile's values are not written over them.
     */
    @Test fun anIdThatAProfilesValuesAreKeptUnderIsNotGivenToTheNextProfile() = runTest {
        store.create("Work", copyOf = null)
        store.setActive(2)
        view.edit { it[baseColour] = "green" }
        store.setActive(1)
        main.updateData { emptyPreferences() }

        val made = store.create("New", copyOf = null)!!

        assertThat(made.id).isEqualTo(3L)
        assertThat(view.profileValues(2)[baseColour]).isEqualTo("green")
        // What the caller knows to be kept under an id (layout rows, pictures) passes it over too.
        assertThat(store.prepare("Next", copyOf = null, kept = setOf(4L, 5L))!!.id).isEqualTo(6L)
    }

    /** A profile is marked as being made only until it is listed: its own values never show the mark, and a
     *  listed profile's store does not carry it. */
    @Test fun theMarkOfBeingMadeIsGoneOnceTheProfileIsListedAndIsNeverOneOfItsValues() = runTest {
        val beingMade = androidx.datastore.preferences.core.booleanPreferencesKey(HomeProfileKeys.BEING_MADE)
        val profile = store.prepare("Work", copyOf = null)!!
        assertThat(files.store(profile.id).now[beingMade]).isTrue()
        assertThat(view.profileValues(profile.id).asMap()).isEmpty()

        assertThat(store.publish(profile)).isTrue()

        assertThat(files.store(profile.id).now[beingMade]).isNull()
    }

    /**
     * Review round 8. A profile's own store that nothing reads any more is dropped after the list has been
     * written. A store that could not be removed threw from there: a restore lost the ids of the profiles it had
     * just removed (their pictures were then never cleared), and a deletion was reported as not done.
     */
    @Test fun aStoreThatCannotBeRemovedUndoesNeitherADeletionNorARestoresClearing() = runTest {
        store.create("Work", copyOf = null)
        store.create("Evening", copyOf = null)
        store.setActive(2)
        files.failDelete = java.io.IOException("the file is in use")

        // The first profile's values are in the settings file: Work's move there, and its own store is left.
        assertThat(store.delete(1)).isTrue()
        assertThat(store.list().map { it.id }).containsExactly(2L, 3L).inOrder()
        assertThat(main.now[mainStore]).isEqualTo(2L)

        store.setActive(3)
        assertThat(store.keepOnlyActive()).containsExactly(2L)
        assertThat(store.list().map { it.id }).containsExactly(3L)
        assertThat(main.now[mainStore]).isEqualTo(3L)
    }

    /**
     * Review round 13. A pointer that names no profile of the list is not followed (the first profile is read),
     * and it must not come true when a profile of that id is made. A settings file that comes from 1.2.1 can
     * hold one, as that version kept a key it did not know. The next profile got exactly that id, and listing
     * it made the pointer a true one: the new profile was on though nobody had switched to it, and the
     * settings file was read as its own while the user's first profile read an empty store. Listing a profile
     * writes both pointers as they were read, in the write that lengthens the list.
     */
    @Test fun aPointerThatNamedNoProfileIsNotFollowedOnceAProfileOfThatIdIsListed() = runTest {
        view.edit { it[baseColour] = "blue" }
        main.updateData {
            it.toMutablePreferences().apply {
                this[mainStore] = 2L
                this[longPreferencesKey(HomeProfileKeys.ACTIVE)] = 2L
            }.toPreferences()
        }
        assertThat(store.current()).isEqualTo(1L)

        val created = store.create("Work", copyOf = null)!!

        assertThat(created.id).isEqualTo(2L)
        // Nobody switched: the first profile is on, and its values are where they were.
        assertThat(store.current()).isEqualTo(1L)
        assertThat(ProfiledPreferences.mainStoreId(main.now)).isEqualTo(1L)
        assertThat(view.data.first()[baseColour]).isEqualTo("blue")
        assertThat(view.profileValues(1)[baseColour]).isEqualTo("blue")
        assertThat(view.profileValues(created.id).names()).isEmpty()
    }

    /**
     * Review round 13. The memory of the highest id given is a number, and the next id is one more. A settings
     * file that comes from 1.2.1 can hold the last number there is under that name (that version kept a key it
     * did not know): one more was then a negative id. The profile was reported as made, and the list, which
     * reads no such id, never showed it. A memory after which no id follows was not written here (this
     * launcher gives ids one by one), and it says nothing: the ids go on from the list.
     */
    @Test fun aMemoryOfTheLastIdThereIsDoesNotMakeTheNextIdANegativeOne() = runTest {
        main.updateData {
            it.toMutablePreferences().apply { this[longPreferencesKey(HomeProfileKeys.LAST_ID)] = Long.MAX_VALUE }.toPreferences()
        }

        val created = store.create("Work", copyOf = null)

        assertThat(created).isEqualTo(HomeProfile(2, "Work"))
        assertThat(store.list()).containsExactly(HomeProfile(1, ""), HomeProfile(2, "Work")).inOrder()
        assertThat(ProfiledPreferences.lastId(main.now)).isEqualTo(2L)
    }

    /** Review round 13. And where the list itself holds the last id there is, no id is left: a profile is not
     *  made, and that is found out before anything of it is written. */
    @Test fun noProfileIsMadeWhenNoIdIsLeft() = runTest {
        main.updateData {
            it.toMutablePreferences().apply {
                this[stringPreferencesKey(HomeProfileKeys.LIST)] = "1\t\n${Long.MAX_VALUE}\tWork"
            }.toPreferences()
        }
        val mainWrites = main.writes

        val failure = runCatching { store.prepare("Next", copyOf = null) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(main.writes).isEqualTo(mainWrites)
        assertThat(files.stores.values.sumOf { it.writes }).isEqualTo(0)
        assertThat(store.list().map { it.id }).containsExactly(1L, Long.MAX_VALUE).inOrder()
    }

    /** Review round 13. A profile the list would not read back is not listed, whoever hands it over: listing it
     *  said "made" of a profile nothing would ever show. */
    @Test fun aProfileTheListWouldNotReadBackIsNotListed() = runTest {
        val mainWrites = main.writes

        assertThat(store.publish(HomeProfile(-3, "Work"))).isFalse()

        assertThat(main.writes).isEqualTo(mainWrites)
        assertThat(files.stores.values.sumOf { it.writes }).isEqualTo(0)
        assertThat(store.list()).containsExactly(HomeProfile(1, ""))
    }

    @Test fun aSwitchWaitsWhileSomeoneNeedsTheProfileToStay() = runTest {
        store.create("Work", copyOf = null)
        var onDuringTheWork = 0L

        store.whileActiveStays { active ->
            assertThat(active).isEqualTo(1L)
            // Asked for in the middle of the work.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.setActive(2) }
            onDuringTheWork = store.current()
        }

        assertThat(onDuringTheWork).isEqualTo(1L)
        // And honoured once the work is done.
        assertThat(store.current()).isEqualTo(2L)
    }

    /** Two profiles, the second one on, each with a look of its own. */
    private suspend fun theSecondProfileIsOn() {
        view.edit { it[baseColour] = "blue" }
        store.create("Work", copyOf = null)
        store.setActive(2)
        view.edit { it[baseColour] = "green" }
    }

    private val unreadable = java.io.IOException("Input/output error")

    /**
     * Review round 14 (P1). A profile whose own store cannot be read is left for the one the settings file
     * holds (round 13), and that is a switch: it was made by whoever read the settings first, also in the
     * middle of work that holds the profile from its first step to its last. A restore confirmed for the
     * second profile then deleted that profile and kept the first; an export of it wrote the first's home
     * screen under the second's name. The profile is left as it is switched: not while work holds it. Work
     * that asks to hold the profile is given one that can be read, at its first step, so whatever it was asked
     * for by name it can tell that this is another profile before it does anything.
     */
    @Test fun workThatHoldsTheProfileThatIsOnIsGivenOneThatCanBeRead() = runTest {
        theSecondProfileIsOn()
        files.store(2).failReads = unreadable

        val held = store.whileActiveStays { active -> active to view.data.first()[baseColour] }

        assertThat(held).isEqualTo(1L to "blue")
        assertThat(store.current()).isEqualTo(1L)
        assertThat(view.leftUnread.value).isEqualTo(2L)
    }

    /** And a store that begins to fail while its profile is held does not change the profile under the work:
     *  the work is told that its profile cannot be read, by the reading and by the change that found it so,
     *  and the profile is left once the work is done. */
    @Test fun aProfileWhoseStoreFailsWhileItIsHeldStaysOnUntilTheWorkIsDone() = runTest {
        theSecondProfileIsOn()

        store.whileActiveStays { active ->
            assertThat(active).isEqualTo(2L)
            files.store(2).failReads = unreadable

            // (The failure itself, as far as its kind and its words go: handed from one coroutine to
            // another it arrives as a copy.)
            val read = runCatching { view.data.first() }.exceptionOrNull()
            assertThat(read).isInstanceOf(java.io.IOException::class.java)
            assertThat(read).hasMessageThat().isEqualTo(unreadable.message)
            val change = runCatching { view.edit { it[longPress] = 0.5f } }.exceptionOrNull()
            assertThat(change).isInstanceOf(java.io.IOException::class.java)
            assertThat(change).hasMessageThat().isEqualTo(unreadable.message)
            assertThat(store.current()).isEqualTo(2L)
        }

        assertThat(main.now[longPress]).isNull()
        assertThat(view.data.first()[baseColour]).isEqualTo("blue")
        assertThat(store.current()).isEqualTo(1L)
    }

    /** A reader that is not the work waits for the work, as a switch does, and leaves the profile after it. */
    @Test fun aReaderOutsideTheWorkLeavesAProfileOnlyAfterTheWorkThatHoldsIt() = runTest(UnconfinedTestDispatcher()) {
        theSecondProfileIsOn()
        val begun = CompletableDeferred<Unit>()
        val mayEnd = CompletableDeferred<Unit>()
        var onAtTheEndOfTheWork = 0L
        val work = launch {
            store.whileActiveStays {
                begun.complete(Unit)
                mayEnd.await()
                onAtTheEndOfTheWork = store.current()
            }
        }
        begun.await()
        files.store(2).failReads = unreadable

        val read = async { view.data.first() }

        // The reading has gone as far as it can: the profile is still the work's.
        assertThat(read.isCompleted).isFalse()
        assertThat(store.current()).isEqualTo(2L)
        mayEnd.complete(Unit)
        work.join()
        assertThat(onAtTheEndOfTheWork).isEqualTo(2L)
        assertThat(read.await()[baseColour]).isEqualTo("blue")
        assertThat(store.current()).isEqualTo(1L)
    }

    /**
     * Review round 14. Leaving a profile is one write to the settings file, and that file can be one that
     * reads and cannot be written (a full disk). The write then threw in place of the reading: no home screen
     * again. The profile that came on is then on for this run without the file having been told: everything
     * that asks which profile is on is given it, a change is for it and fails as any change to that file
     * does, with nothing written to the profile that was left, and the first write the file takes tells it.
     */
    @Test fun aProfileIsLeftForThisRunWhenTheSettingsFileCannotBeToldOfIt() = runTest {
        theSecondProfileIsOn()
        val ownWrites = files.store(2).writes
        files.store(2).failReads = unreadable
        main.failWrites = java.io.IOException("No space left on device")

        val seen = view.data.first()

        assertThat(seen[baseColour]).isEqualTo("blue")
        assertThat(ProfiledPreferences.activeId(seen)).isEqualTo(1L)
        assertThat(store.current()).isEqualTo(1L)
        assertThat(store.id.first()).isEqualTo(1L)
        assertThat(view.leftUnread.value).isEqualTo(2L)
        assertThat(ProfiledPreferences.activeId(main.now)).isEqualTo(2L)
        assertThat(runCatching { view.edit { it[longPress] = 0.5f } }.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        assertThat(files.store(2).writes).isEqualTo(ownWrites)

        main.failWrites = null
        view.edit { it[longPress] = 0.5f }
        assertThat(ProfiledPreferences.activeId(main.now)).isEqualTo(1L)
        assertThat(main.now[longPress]).isEqualTo(0.5f)
        assertThat(store.current()).isEqualTo(1L)
    }

    /** And the profile that was left can be switched back to, when its store reads again, though the file
     *  was never told that it had been left: it says that profile is on, as it did all along. */
    @Test fun aProfileLeftForThisRunOnlyIsSwitchedBackToAsAnyOther() = runTest {
        theSecondProfileIsOn()
        files.store(2).failReads = unreadable
        main.failWrites = java.io.IOException("No space left on device")
        assertThat(view.data.first()[baseColour]).isEqualTo("blue")

        files.store(2).failReads = null
        main.failWrites = null
        store.setActive(2)

        assertThat(store.current()).isEqualTo(2L)
        assertThat(store.id.first()).isEqualTo(2L)
        assertThat(view.data.first()[baseColour]).isEqualTo("green")
    }
}
