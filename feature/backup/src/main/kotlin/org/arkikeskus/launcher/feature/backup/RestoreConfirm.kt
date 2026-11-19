package org.arkikeskus.launcher.feature.backup

import android.content.res.Resources
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.arkikeskus.launcher.model.HomeProfile
import javax.inject.Inject

/**
 * The home profiles a restore deletes, by name: a backup file holds one profile, and a restore leaves the one
 * that is on, with the file's content. Empty for a launcher with one profile.
 */
fun interface ProfilesLostInRestore {
    fun names(): Flow<List<String>>
}

/** [ProfilesLostInRestore] from the stored profiles: every one but the one that is on. */
internal class StoredProfilesLostInRestore @Inject constructor(
    private val profiles: HomeProfileRepository,
) : ProfilesLostInRestore {
    override fun names(): Flow<List<String>> = combine(profiles.profiles, profiles.activeId, ::profilesARestoreDeletes)
}

/** Every profile of [profiles] but the one that is on ([activeId]), by name, in the list's order. */
internal fun profilesARestoreDeletes(profiles: List<HomeProfile>, activeId: Long): List<String> =
    profiles.filter { it.id != activeId }.map { it.name }

/**
 * What the restore's question says: what it always said, and which profiles go ([deletedProfiles]) if any do.
 * A profile without a name of its own (the first one, until the user names it) goes under the name it is
 * shown with everywhere, not under its empty stored one.
 */
internal fun restoreConfirmText(resources: Resources, deletedProfiles: List<String>): String {
    val question = resources.getString(R.string.backup_restore_confirm_msg)
    if (deletedProfiles.isEmpty()) return question
    val names = deletedProfiles.joinToString(", ") { name ->
        name.ifBlank { resources.getString(org.arkikeskus.launcher.ui.R.string.profile_default_name) }
    }
    return question + "\n\n" + resources.getString(R.string.backup_restore_confirm_profiles, names)
}

/**
 * The backup screen's question before a restore. [deletedProfiles] is null until the profiles have been read:
 * the question cannot be answered with Restore before it can say what goes.
 */
@Composable
internal fun RestoreConfirmDialog(deletedProfiles: List<String>?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
        text = { Text(restoreConfirmText(LocalResources.current, deletedProfiles.orEmpty())) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = deletedProfiles != null) {
                Text(stringResource(R.string.backup_restore_confirm_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        },
    )
}
