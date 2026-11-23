package org.arkikeskus.launcher.feature.backup

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.arkikeskus.launcher.data.backup.ConfirmedProfiles
import org.arkikeskus.launcher.model.HomeProfile
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Open with" import: ask, read the file (the user may still leave), replace the layout (they
 * may not: that step always finishes), then show how it went.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val file = Uri.parse("content://org.telegram.messenger.provider/media/backup.json")

    /** A launcher with the one profile: a restore deletes none. */
    private val shown = RestoreProfiles(activeId = 1, deleted = emptyList())
    private val oneProfile = ProfilesLostInRestore { flowOf(shown) }

    /** Reads until [read] completes, then writes until [result] completes. */
    private class FakeRestorer : BackupRestorer {
        val restored = mutableListOf<Uri>()
        val agreedTo = mutableListOf<ConfirmedProfiles>()
        val read = CompletableDeferred<Unit>()
        val result = CompletableDeferred<BackupEvent>()
        var cancelledWhileReading = false

        override suspend fun restoreFrom(uri: Uri, confirmed: ConfirmedProfiles, onWriting: () -> Unit): BackupEvent {
            restored += uri
            agreedTo += confirmed
            try {
                read.await()
            } catch (e: CancellationException) {
                cancelledWhileReading = true
                throw e
            }
            onWriting()
            return result.await()
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun nothingIsRestoredBeforeTheUserConfirms() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer, oneProfile)
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(BackupImportState.Confirm)
        assertThat(restorer.restored).isEmpty()
    }

    @Test fun confirmingReadsThenRestoresAndShowsTheOutcome() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer, oneProfile)
        advanceUntilIdle()

        vm.restore(file, shown)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(BackupImportState.Reading)
        assertThat(restorer.restored).containsExactly(file)

        restorer.read.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(BackupImportState.Restoring)

        restorer.result.complete(BackupEvent.Restored(restored = 7, skipped = 1))
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(BackupImportState.Done(BackupEvent.Restored(restored = 7, skipped = 1)))
    }

    /** A provider that never answers must not trap the user: leaving while the file is read stops it. */
    @Test fun leavingWhileTheFileIsReadStopsTheImport() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer, oneProfile)
        advanceUntilIdle()
        vm.restore(file, shown)
        advanceUntilIdle()

        assertThat(vm.leave()).isTrue()
        advanceUntilIdle()

        assertThat(restorer.cancelledWhileReading).isTrue()
    }

    /** Once the layout is being replaced the user waits for it: the step is short and always finishes. */
    @Test fun leavingWhileTheLayoutIsReplacedIsRefused() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer, oneProfile)
        advanceUntilIdle()
        vm.restore(file, shown)
        restorer.read.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.leave()).isFalse()
        restorer.result.complete(BackupEvent.Restored(restored = 1, skipped = 0))
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(BackupImportState.Done(BackupEvent.Restored(restored = 1, skipped = 0)))
        assertThat(vm.leave()).isTrue()
    }

    @Test fun leavingBeforeConfirmingRestoresNothing() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer, oneProfile)
        advanceUntilIdle()

        assertThat(vm.leave()).isTrue()
        vm.restore(file, shown)
        advanceUntilIdle()

        assertThat(restorer.restored).isEmpty()
    }

    /** A double tap on Restore, or a tap that lands after the outcome, never restores twice. */
    @Test fun theFileIsRestoredOnlyOnce() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer, oneProfile)
        advanceUntilIdle()

        vm.restore(file, shown)
        vm.restore(file, shown)
        restorer.read.complete(Unit)
        advanceUntilIdle()
        restorer.result.complete(BackupEvent.ReadBlocked)
        advanceUntilIdle()
        vm.restore(file, shown)
        advanceUntilIdle()

        assertThat(restorer.restored).containsExactly(file)
        assertThat(vm.state.value).isEqualTo(BackupImportState.Done(BackupEvent.ReadBlocked))
    }

    /** A restore deletes every home profile but the one that is on: the question names them. Until they have
     *  been read there is nothing to name, and the screen keeps Restore back. */
    @Test fun theProfilesARestoreDeletesAreUnknownUntilTheyAreRead() = runTest(dispatcher) {
        val profiles = MutableSharedFlow<RestoreProfiles>()
        val vm = BackupImportViewModel(FakeRestorer(), ProfilesLostInRestore { profiles })
        advanceUntilIdle()
        assertThat(vm.profileReader.state.value.profiles).isNull()

        profiles.emit(RestoreProfiles(1, listOf(HomeProfile(2, "Work"), HomeProfile(3, "Evening"))))
        advanceUntilIdle()

        assertThat(vm.profileReader.state.value.profiles?.names).containsExactly("Work", "Evening").inOrder()
    }

    @Test fun withOneProfileARestoreDeletesNone() = runTest(dispatcher) {
        val vm = BackupImportViewModel(FakeRestorer(), oneProfile)
        advanceUntilIdle()

        assertThat(vm.profileReader.state.value.profiles?.names).isEmpty()
    }

    /**
     * The restore is asked for with the profiles its question showed: which one is on, and which ones it
     * deletes. The file is read after the question, and the profiles can change meanwhile (one made in the
     * launcher's Settings while the file was still coming); the restore itself then refuses, by these.
     */
    @Test fun theRestoreCarriesTheProfilesItsQuestionShowed() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer, oneProfile)
        advanceUntilIdle()
        val asked = RestoreProfiles(activeId = 2, deleted = listOf(HomeProfile(1, ""), HomeProfile(3, "Evening")))

        vm.restore(file, asked)
        advanceUntilIdle()

        assertThat(restorer.agreedTo).containsExactly(ConfirmedProfiles(activeId = 2, otherIds = setOf(1L, 3L)))
    }

    @Test fun unreadableProfilesDoNotCrashAnOpenWithQuestion() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        var broken = true
        val vm = BackupImportViewModel(restorer, ProfilesLostInRestore {
            kotlinx.coroutines.flow.flow {
                if (broken) throw java.io.IOException("cannot read profiles")
                emit(shown)
            }
        })
        advanceUntilIdle()
        assertThat(vm.profileReader.state.value).isEqualTo(RestoreProfileRead.Failed)
        vm.restore(file, shown)
        advanceUntilIdle()
        assertThat(restorer.restored).isEmpty()
        broken = false
        vm.profileReader.retry()
        vm.restore(file, shown)
        advanceUntilIdle()
        assertThat(restorer.restored).isEmpty()
        assertThat(vm.profileReader.state.value.profiles).isEqualTo(shown)
    }
}
