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

    @Test fun aTimedOutReadSaysSo() {
        assertThat(backupEventMessage(res, BackupEvent.ReadTimedOut)).isEqualTo(res.getString(R.string.backup_import_timeout))
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

    /** The backup is in, but the list of profiles could not be written: the other profiles are still there, empty.
     *  The text says the backup was restored, that they are left and where to delete them, and it is long enough
     *  to need its time. A plain "Backup restored" would call a half-done restore done. */
    @Test fun aRestoreThatLeftOtherProfilesSaysSoAndWhereToDeleteThem() {
        val left = BackupEvent.Restored(5, 0, profilesLeft = 2)
        val text = backupEventMessage(res, left)

        assertThat(text).startsWith(res.getString(R.string.backup_restored))
        assertThat(text).contains(res.getQuantityString(R.plurals.backup_restore_profiles_left, 2, 2))
        assertThat(text).contains("2 other profiles could not be deleted")
        assertThat(left.needsTimeToRead).isTrue()
        // With items that were skipped too, both are said.
        val both = backupEventMessage(res, BackupEvent.Restored(5, 3, profilesLeft = 1))
        assertThat(both).startsWith(res.getQuantityString(R.plurals.backup_restored_skipped, 3, 3))
        assertThat(both).contains("1 other profile could not be deleted")
        // A restore that left none says what it always did, and is short.
        assertThat(BackupEvent.Restored(5, 3).needsTimeToRead).isFalse()
    }

    /**
     * Review round 14. The backup is in and the other profiles are gone from the list, but the picture behind
     * the app drawer of one of them could not be removed from the disk. The result said nothing of it: said
     * now with the result, on a line of its own, with what becomes of the picture, and long enough to need its
     * time. Everything a restore's result says reaches the screen.
     */
    @Test fun aRestoreThatLeftAPictureOfAProfileThatWentSaysSo() {
        val left = BackupEvent.Restored(5, 0, picturesLeft = 1)
        val text = backupEventMessage(res, left)

        assertThat(text).startsWith(res.getString(R.string.backup_restored))
        assertThat(text).contains(res.getQuantityString(R.plurals.backup_restore_pictures_left, 1, 1))
        assertThat(text).contains("The next restore removes it")
        assertThat(left.needsTimeToRead).isTrue()
        // With profiles that are still listed as well, both are said.
        val both = backupEventMessage(res, BackupEvent.Restored(5, 0, profilesLeft = 1, picturesLeft = 2))
        assertThat(both).contains("1 other profile could not be deleted")
        assertThat(both).contains(res.getQuantityString(R.plurals.backup_restore_pictures_left, 2, 2))

        assertThat(restoredEvent(org.arkikeskus.launcher.data.backup.RestoreResult(5, 1, profilesLeft = 2, picturesLeft = 3)))
            .isEqualTo(BackupEvent.Restored(5, 1, profilesLeft = 2, picturesLeft = 3))
    }

    @Test fun aRestoreThatCouldNotClearProfileSettingsShowsTheWarning() {
        val result = org.arkikeskus.launcher.data.backup.RestoreResult(2, 0, settingsCleanupIncomplete = true)
        val event = restoredEvent(result)
        assertThat(backupEventMessage(res, event)).contains("profile settings")
        assertThat(event.needsTimeToRead).isTrue()
    }

    /** Nothing was restored because the profiles changed under the question: the text says so and what to do,
     *  and it is long enough to need its time. */
    @Test fun changedProfilesSayThatNothingWasRestored() {
        val text = backupEventMessage(res, BackupEvent.ProfilesChanged)

        assertThat(text).isEqualTo(res.getString(R.string.backup_restore_profiles_changed))
        assertThat(text).contains("nothing was restored")
        assertThat(BackupEvent.ProfilesChanged.needsTimeToRead).isTrue()
    }
}
