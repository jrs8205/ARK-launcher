package org.arkikeskus.launcher.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Each further home profile keeps its own values in a file of its own, which goes with the profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiskHomeProfileFilesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = DiskHomeProfileFiles(context, scope)
    private val baseColour = stringPreferencesKey("base_colour")

    @After fun tearDown() {
        scope.cancel()
    }

    @Test fun theSameIdGivesTheSameStoreAndAnotherIdAnother() {
        assertThat(files.store(2)).isSameInstanceAs(files.store(2))
        assertThat(files.store(3)).isNotSameInstanceAs(files.store(2))
    }

    @Test fun aProfilesValuesAreInAFileOfItsOwn() = runBlocking {
        files.store(2).edit { it[baseColour] = "green" }
        files.store(3).edit { it[baseColour] = "red" }

        assertThat(context.preferencesDataStoreFile("home_profile_2").exists()).isTrue()
        assertThat(context.preferencesDataStoreFile("home_profile_3").exists()).isTrue()
        assertThat(files.store(2).data.first()[baseColour]).isEqualTo("green")
        assertThat(files.store(3).data.first()[baseColour]).isEqualTo("red")
    }

    @Test fun aDeletedProfilesFileIsGoneAndItsIdWouldStartEmpty() = runBlocking {
        files.store(2).edit { it[baseColour] = "green" }

        files.delete(2)

        assertThat(context.preferencesDataStoreFile("home_profile_2").exists()).isFalse()
        assertThat(files.store(2).data.first().asMap()).isEmpty()
    }

    /**
     * The profiles a file is kept for are read from the disk itself, not from the list of profiles (which is in
     * the settings file and is lost with it): the files that are there, by the ids in their names, and nothing
     * else that happens to lie beside them.
     */
    @Test fun theProfilesAFileIsKeptForAreThoseOfTheFilesOnTheDisk(): Unit = runBlocking {
        assertThat(files.ids()).isEmpty()
        files.store(3).edit { it[baseColour] = "red" }
        files.store(12).edit { it[baseColour] = "green" }
        // The settings file itself, and a file whose name only resembles a profile's.
        context.preferencesDataStoreFile("settings").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(0))
        context.preferencesDataStoreFile("home_profile_x").writeBytes(ByteArray(0))

        assertThat(files.ids()).containsExactly(3L, 12L).inOrder()

        files.delete(3)
        assertThat(files.ids()).containsExactly(12L)
    }

    /**
     * Review round 10. A folder that cannot be read gives no listing at all (null), and that was read as "no
     * stores": the pictures of a profile the list had already lost were then used by nobody. It is a failure,
     * and says so; a folder that is not there yet is the one case of "none".
     */
    @Test fun aFolderThatCannotBeReadIsNotAFolderWithNoStores(): Unit = runBlocking {
        files.store(3).edit { it[baseColour] = "red" }
        val unreadable = DiskHomeProfileFiles(context, scope, namesIn = { null })

        val failure = runCatching { unreadable.ids() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        assertThat(files.ids()).containsExactly(3L)
    }

    @Test fun beforeAnyStoreWasWrittenThereAreNone(): Unit = runBlocking {
        context.preferencesDataStoreFile("home_profile_0").parentFile?.deleteRecursively()

        assertThat(DiskHomeProfileFiles(context, scope, namesIn = { error("there is no folder to read") }).ids()).isEmpty()
    }

    /**
     * Review round 13, second run, with the stores the launcher has on a phone. A store whose file cannot be
     * read (here a folder lies where the file should be) throws at its first reading, and the profile it
     * belongs to was on. The settings as the launcher reads them then come from the profile whose values the
     * settings file holds, which is on from there; what lies where the store should be is left as it is.
     */
    @Test fun aStoreFileThatCannotBeReadLeavesItsProfileForTheOneInTheSettingsFile(): Unit = runBlocking {
        val active = longPreferencesKey(HomeProfileKeys.ACTIVE)
        val settingsFile = PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile("settings_of_this_test")
        }
        settingsFile.edit {
            it[stringPreferencesKey(HomeProfileKeys.LIST)] = "1\t\n2\tWork"
            it[active] = 2L
            it[baseColour] = "blue"
        }
        val broken = context.preferencesDataStoreFile("home_profile_2")
        assertThat(broken.mkdirs()).isTrue()

        val view = ProfiledPreferences(settingsFile, files)
        val seen = view.data.first()

        assertThat(seen[baseColour]).isEqualTo("blue")
        assertThat(seen[active]).isEqualTo(1L)
        assertThat(settingsFile.data.first()[active]).isEqualTo(1L)
        assertThat(broken.isDirectory).isTrue()
        // Review round 14. And the profile that was left is named for the user to be told of: a settings
        // file on the disk hands its new values on before the write that made them has returned, the reader
        // that made the write is ended by that very news, and what it did after the write (naming the profile)
        // never happened. Over a store in memory the same reader got to the end.
        assertThat(view.leftUnread.value).isEqualTo(2L)
    }

    /**
     * Review round 15, with the stores on the disk. A reader finds the store of the profile that is on
     * failing (a folder lies where its file should be) while something else holds the profile; by the
     * reader's turn the folder is gone and the store reads. The profile is not left on the word of a failure
     * that has passed: the reader reads on from it, and nobody is told of a profile that was left.
     */
    @Test fun aStoreFileThatReadsAgainBeforeItsProfileIsLeftKeepsItsProfileOn(): Unit = runBlocking {
        val active = longPreferencesKey(HomeProfileKeys.ACTIVE)
        val settingsFile = PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile("settings_of_this_test")
        }
        settingsFile.edit {
            it[stringPreferencesKey(HomeProfileKeys.LIST)] = "1\t\n2\tWork"
            it[active] = 2L
            it[baseColour] = "blue"
        }
        val broken = context.preferencesDataStoreFile("home_profile_2")
        assertThat(broken.mkdirs()).isTrue()
        // Review round 16: the test KNOWS that the reader was handed the store's failure before the store is
        // made whole. It used to give the reader half a second for that: on a busy machine the reader came to
        // the disk after the folder had gone, found a store that read, and the test passed without the fix.
        val failed = CompletableDeferred<IOException>()
        val view = ProfiledPreferences(settingsFile, SayingWhenAReadingFails(files, failed))

        view.hold.lock()
        val read = async(Dispatchers.Default) { view.data.first() }
        val failure = withTimeout(10_000) { failed.await() }
        assertThat(failure).hasMessageThat().contains("home_profile_2")
        assertThat(read.isCompleted).isFalse()
        assertThat(broken.delete()).isTrue()
        view.hold.unlock()
        val seen = withTimeout(10_000) { read.await() }

        assertThat(seen[active]).isEqualTo(2L)
        // The second profile's own values (it has none), not the first one's blue.
        assertThat(seen[baseColour]).isNull()
        assertThat(settingsFile.data.first()[active]).isEqualTo(2L)
        assertThat(view.leftUnread.value).isNull()
    }

    /**
     * Review round 15, with the stores on the disk: the list is one profile, and what lies where the second
     * profile's store should be cannot be read (a folder). A new profile is made all the same, under the
     * next id, and what lies there is left as it is.
     */
    @Test fun aNewProfileIsMadePastAStoreFileThatCannotBeRead(): Unit = runBlocking {
        val settingsFile = PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile("settings_of_this_test")
        }
        val broken = context.preferencesDataStoreFile("home_profile_2")
        assertThat(broken.mkdirs()).isTrue()
        val store = HomeProfileStore(settingsFile, ProfiledPreferences(settingsFile, files), files)

        val created = store.create("Work", copyOf = null)

        assertThat(created?.id).isEqualTo(3L)
        assertThat(store.list().map { it.id }).containsExactly(1L, 3L).inOrder()
        assertThat(broken.isDirectory).isTrue()
    }

    /** The stores of [real], each saying through [failed] when a reading of it has failed: the first such
     *  failure, as the reader was handed it. */
    private class SayingWhenAReadingFails(
        private val real: HomeProfileFiles,
        private val failed: CompletableDeferred<IOException>,
    ) : HomeProfileFiles by real {
        override fun store(profileId: Long): DataStore<Preferences> {
            val store = real.store(profileId)
            return object : DataStore<Preferences> {
                override val data: Flow<Preferences> = store.data.catch { failure ->
                    if (failure is IOException) failed.complete(failure)
                    throw failure
                }

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    store.updateData(transform)
            }
        }
    }

    @Test fun deletingAProfileThatNeverWroteAnythingIsFine() = runBlocking {
        files.delete(5)

        assertThat(context.preferencesDataStoreFile("home_profile_5").exists()).isFalse()
    }
}
