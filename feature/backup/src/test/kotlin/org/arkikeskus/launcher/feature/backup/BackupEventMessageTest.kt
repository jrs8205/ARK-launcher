package org.arkikeskus.launcher.feature.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The text a backup event shows, shared by the backup screen and the "Open with" import. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupEventMessageTest {
    private val res = RuntimeEnvironment.getApplication().resources

    @Test fun aBlockedReadTellsWhatToDoInsteadOfTheSystemMessage() {
        val text = backupEventMessage(res, BackupEvent.ReadBlocked)

        assertThat(text).isEqualTo(res.getString(R.string.backup_import_read_blocked))
        assertThat(text).doesNotContain("content://")
    }

    @Test fun onlyTheBlockedReadNeedsTimeToBeRead() {
        assertThat(BackupEvent.ReadBlocked.needsTimeToRead).isTrue()
        assertThat(BackupEvent.InvalidFile.needsTimeToRead).isFalse()
        assertThat(BackupEvent.Failed("x").needsTimeToRead).isFalse()
        assertThat(BackupEvent.Restored(3, 0).needsTimeToRead).isFalse()
    }

    @Test fun theOtherEventsKeepTheirTexts() {
        assertThat(backupEventMessage(res, BackupEvent.Exported("b.json"))).isEqualTo(res.getString(R.string.backup_exported))
        assertThat(backupEventMessage(res, BackupEvent.Restored(5, 0))).isEqualTo(res.getString(R.string.backup_restored))
        assertThat(backupEventMessage(res, BackupEvent.Restored(5, 2)))
            .isEqualTo(res.getQuantityString(R.plurals.backup_restored_skipped, 2, 2))
        assertThat(backupEventMessage(res, BackupEvent.InvalidFile)).isEqualTo(res.getString(R.string.backup_import_invalid))
        assertThat(backupEventMessage(res, BackupEvent.TooLarge)).isEqualTo(res.getString(R.string.backup_error_too_large))
        assertThat(backupEventMessage(res, BackupEvent.Failed(""))).isEqualTo(res.getString(R.string.backup_failed))
        assertThat(backupEventMessage(res, BackupEvent.Failed("disk full")))
            .isEqualTo(res.getString(R.string.backup_failed) + ": disk full")
    }
}
