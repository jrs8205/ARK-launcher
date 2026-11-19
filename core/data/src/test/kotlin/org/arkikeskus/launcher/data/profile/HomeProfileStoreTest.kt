package org.arkikeskus.launcher.data.profile

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        files.store(2).edit { it[baseColour] = "blue"; it[homeColumns] = 6 }

        val made = store.create("Evening", copyOf = null)!!

        assertThat(made.id).isEqualTo(2L)
        assertThat(view.profileValues(2).asMap()).isEmpty()
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
}
