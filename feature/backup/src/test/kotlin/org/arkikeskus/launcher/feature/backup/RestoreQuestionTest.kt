package org.arkikeskus.launcher.feature.backup

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.HomeProfile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review round 11. What a restore's question names comes partly from files (a profile's own store, its drawer
 * picture, kept under an id the list of profiles does not name), and files do not say when they change. The
 * value was read when the backup screen opened and again only when the rows or the list changed: after a
 * listing that had failed (the id left out of the question), a disk that was readable again changed nothing
 * the value followed. The restore refused, rightly, and the next question on the same screen was the same one:
 * every try refused. The profiles are read again for each question, and after a restore that found them changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreQuestionTest {
    private val file = Uri.parse("content://documents/backup.json")
    private val list = listOf(HomeProfile(1, ""))

    /** What the files say right now: nothing (they could not be listed), then the store kept under id 2. */
    private var onDisk = profilesARestoreDeletes(list, activeId = 1)
    private val whole = profilesARestoreDeletes(list, activeId = 1, unlisted = setOf(2))

    /** As the stored profiles are followed: read when the following begins, then nothing more, since nothing
     *  the following is told of (the rows, the list) changes in these tests. */
    private var readings = 0
    private val lost = ProfilesLostInRestore {
        flow {
            readings++
            emit(onDisk)
            awaitCancellation()
        }
    }

    private var outcome: BackupEvent = BackupEvent.ProfilesChanged
    private val restorer = BackupRestorer { _, _, _ -> outcome }

    private fun TestScope.question() =
        RestoreQuestion(lost, restorer, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    @Test fun theProfilesAreReadAgainWhenTheQuestionIsOpened() = runTest {
        val question = question()
        assertThat(question.profiles.value).isEqualTo(onDisk)

        onDisk = whole
        assertThat(question.profiles.value).isNotEqualTo(whole)
        question.opened()

        assertThat(question.profiles.value).isEqualTo(whole)
    }

    @Test fun aRestoreThatFoundTheProfilesChangedHasThemReadAgain() = runTest {
        val question = question()
        val shown = question.profiles.value!!
        onDisk = whole

        assertThat(question.restore(file, shown)).isEqualTo(BackupEvent.ProfilesChanged)

        // The next question on this screen names what the restore met.
        assertThat(question.profiles.value).isEqualTo(whole)
    }

    /** Any other outcome leaves the value followed as it was: nothing is read for nothing. */
    @Test fun aRestoreThatWentThroughReadsNothingAgain() = runTest {
        val question = question()
        val shown = question.profiles.value!!
        outcome = BackupEvent.InvalidFile
        val before = readings

        assertThat(question.restore(file, shown)).isEqualTo(BackupEvent.InvalidFile)

        assertThat(readings).isEqualTo(before)
    }
}
