package org.arkikeskus.launcher.data.profile

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    @Test fun deletingAProfileThatNeverWroteAnythingIsFine() = runBlocking {
        files.delete(5)

        assertThat(context.preferencesDataStoreFile("home_profile_5").exists()).isFalse()
    }
}
