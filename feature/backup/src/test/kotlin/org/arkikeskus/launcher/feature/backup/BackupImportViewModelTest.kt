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
    private val oneProfile = ProfilesLostInRestore { flowOf(emptyList()) }

    /** Reads until [read] completes, then writes until [result] completes. */
    private class FakeRestorer : BackupRestorer {
        val restored = mutableListOf<Uri>()
        val read = CompletableDeferred<Unit>()
        val result = CompletableDeferred<BackupEvent>()
        var cancelledWhileReading = false

        override suspend fun restoreFrom(uri: Uri, onWriting: () -> Unit): BackupEvent {
            restored += uri
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

        vm.restore(file)
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
        vm.restore(file)
        advanceUntilIdle()

        assertThat(vm.leave()).isTrue()
        advanceUntilIdle()

        assertThat(restorer.cancelledWhileReading).isTrue()
    }

    /** Once the layout is being replaced the user waits for it: the step is short and always finishes. */
    @Test fun leavingWhileTheLayoutIsReplacedIsRefused() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer, oneProfile)
        vm.restore(file)
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

        assertThat(vm.leave()).isTrue()
        vm.restore(file)
        advanceUntilIdle()

        assertThat(restorer.restored).isEmpty()
    }

    /** A double tap on Restore, or a tap that lands after the outcome, never restores twice. */
    @Test fun theFileIsRestoredOnlyOnce() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer, oneProfile)

        vm.restore(file)
        vm.restore(file)
        restorer.read.complete(Unit)
        advanceUntilIdle()
        restorer.result.complete(BackupEvent.ReadBlocked)
        advanceUntilIdle()
        vm.restore(file)
        advanceUntilIdle()

        assertThat(restorer.restored).containsExactly(file)
        assertThat(vm.state.value).isEqualTo(BackupImportState.Done(BackupEvent.ReadBlocked))
    }

    /** A restore deletes every home profile but the one that is on: the question names them. Until they have
     *  been read there is nothing to name, and the screen keeps Restore back. */
    @Test fun theProfilesARestoreDeletesAreUnknownUntilTheyAreRead() = runTest(dispatcher) {
        val profiles = MutableSharedFlow<List<String>>()
        val vm = BackupImportViewModel(FakeRestorer(), ProfilesLostInRestore { profiles })
        advanceUntilIdle()
        assertThat(vm.deletedProfiles.value).isNull()

        profiles.emit(listOf("Work", "Evening"))
        advanceUntilIdle()

        assertThat(vm.deletedProfiles.value).containsExactly("Work", "Evening").inOrder()
    }

    @Test fun withOneProfileARestoreDeletesNone() = runTest(dispatcher) {
        val vm = BackupImportViewModel(FakeRestorer(), oneProfile)
        advanceUntilIdle()

        assertThat(vm.deletedProfiles.value).isEmpty()
    }
}
