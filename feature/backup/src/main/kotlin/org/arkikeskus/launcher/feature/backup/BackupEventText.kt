package org.arkikeskus.launcher.feature.backup

import android.content.res.Resources
import org.arkikeskus.launcher.data.backup.RestoreResult

/** What the screen is told of a restore that went through: everything [result] says. */
internal fun restoredEvent(result: RestoreResult): BackupEvent.Restored =
    BackupEvent.Restored(result.restored, result.skipped, result.profilesLeft, result.picturesLeft, result.settingsCleanupIncomplete)

/** The text [event] shows: on the backup screen's snackbar and in the "Open with" import's dialog. */
internal fun backupEventMessage(res: Resources, event: BackupEvent): String = when (event) {
    is BackupEvent.Exported -> {
        // With several profiles the result says which one the file is of.
        val profile = event.profile
        if (profile == null) {
            res.getString(R.string.backup_exported)
        } else {
            res.getString(R.string.backup_exported_profile, shownProfileName(res, profile))
        }
    }
    is BackupEvent.Restored -> {
        val restored = if (event.skipped > 0) {
            res.getQuantityString(R.plurals.backup_restored_skipped, event.skipped, event.skipped)
        } else {
            res.getString(R.string.backup_restored)
        }
        // The backup is in, but other profiles are still listed, or a picture of one that went is still on the
        // disk: each said with it, on a line of its own.
        listOfNotNull(
            restored,
            res.getQuantityString(R.plurals.backup_restore_profiles_left, event.profilesLeft, event.profilesLeft)
                .takeIf { event.profilesLeft > 0 },
            res.getQuantityString(R.plurals.backup_restore_pictures_left, event.picturesLeft, event.picturesLeft)
                .takeIf { event.picturesLeft > 0 },
            res.getString(R.string.backup_restore_profile_settings_cleanup).takeIf { event.settingsCleanupIncomplete },
        ).joinToString("\n")
    }
    BackupEvent.InvalidFile -> res.getString(R.string.backup_import_invalid)
    BackupEvent.TooLarge -> res.getString(R.string.backup_error_too_large)
    BackupEvent.ReadBlocked -> res.getString(R.string.backup_import_read_blocked)
    BackupEvent.ReadTimedOut -> res.getString(R.string.backup_import_timeout)
    BackupEvent.ProfilesChanged -> res.getString(R.string.backup_restore_profiles_changed)
    BackupEvent.ExportProfileChanged -> res.getString(R.string.backup_export_profile_changed)
    is BackupEvent.Failed -> {
        val failed = res.getString(R.string.backup_failed)
        if (event.message.isBlank()) failed else "$failed: ${event.message}"
    }
}
