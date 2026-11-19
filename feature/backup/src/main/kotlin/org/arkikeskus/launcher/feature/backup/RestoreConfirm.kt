package org.arkikeskus.launcher.feature.backup

import android.content.res.Resources
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.arkikeskus.launcher.data.backup.ConfirmedProfiles
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.arkikeskus.launcher.model.HomeProfile
import javax.inject.Inject

/**
 * The home profiles as a restore meets them: the one that is on ([activeId]), which it fills with the
 * backup, and the others ([deleted]), which it deletes: a backup file holds one profile.
 */
data class RestoreProfiles(val activeId: Long, val deleted: List<HomeProfile>) {
    /** The names the question shows (an unnamed one is empty here; [restoreConfirmText] names it). */
    val names: List<String> get() = deleted.map { it.name }

    /** What the restore is held to once the question is answered: these profiles, or nothing is written. */
    val confirmed: ConfirmedProfiles get() = ConfirmedProfiles(activeId, deleted.map { it.id }.toSet())
}

/** The profiles a restore would meet now ([RestoreProfiles]); [deleted][RestoreProfiles.deleted] is empty for a
 *  launcher with one profile. */
fun interface ProfilesLostInRestore {
    fun profiles(): Flow<RestoreProfiles>
}

/** [ProfilesLostInRestore] from the stored profiles: every one but the one that is on. */
internal class StoredProfilesLostInRestore @Inject constructor(
    private val profiles: HomeProfileRepository,
) : ProfilesLostInRestore {
    override fun profiles(): Flow<RestoreProfiles> = combine(profiles.profiles, profiles.activeId, ::profilesARestoreDeletes)
}

/** The one that is on ([activeId]) and every other profile of [profiles], in the list's order. */
internal fun profilesARestoreDeletes(profiles: List<HomeProfile>, activeId: Long): RestoreProfiles =
    RestoreProfiles(activeId, profiles.filter { it.id != activeId })

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
        // It scrolls: with many profiles to name, on a small window at a large font, the text is taller
        // than the dialog, and a cut text let Restore be tapped without the last names having been seen.
        text = {
            Text(
                restoreConfirmText(LocalResources.current, deletedProfiles.orEmpty()),
                Modifier.verticalScroll(rememberScrollState()),
            )
        },
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
