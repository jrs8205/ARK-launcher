package org.arkikeskus.launcher.feature.backup

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** The "Open with" import: ask, restore once, then show how it went. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val file = Uri.parse("content://org.telegram.messenger.provider/media/backup.json")

    private class FakeRestorer : BackupRestorer {
        val restored = mutableListOf<Uri>()
        val result = CompletableDeferred<BackupEvent>()
        override suspend fun restoreFrom(uri: Uri): BackupEvent {
            restored += uri
            return result.await()
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun nothingIsRestoredBeforeTheUserConfirms() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer)
        advanceUntilIdle()

        assertThat(vm.state.value).isEqualTo(BackupImportState.Confirm)
        assertThat(restorer.restored).isEmpty()
    }

    @Test fun confirmingRestoresTheFileAndShowsTheOutcome() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer)

        vm.restore(file)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(BackupImportState.Running)
        assertThat(restorer.restored).containsExactly(file)

        restorer.result.complete(BackupEvent.Restored(restored = 7, skipped = 1))
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(BackupImportState.Done(BackupEvent.Restored(restored = 7, skipped = 1)))
    }

    /** A double tap on Restore, or a tap that lands after the outcome, never restores twice. */
    @Test fun theFileIsRestoredOnlyOnce() = runTest(dispatcher) {
        val restorer = FakeRestorer()
        val vm = BackupImportViewModel(restorer)

        vm.restore(file)
        vm.restore(file)
        advanceUntilIdle()
        restorer.result.complete(BackupEvent.ReadBlocked)
        advanceUntilIdle()
        vm.restore(file)
        advanceUntilIdle()

        assertThat(restorer.restored).containsExactly(file)
        assertThat(vm.state.value).isEqualTo(BackupImportState.Done(BackupEvent.ReadBlocked))
    }
}
