package org.arkikeskus.launcher.feature.backup

import android.content.res.Resources
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.arkikeskus.launcher.data.backup.ConfirmedProfiles
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.arkikeskus.launcher.model.HomeProfile
import javax.inject.Inject

/**
 * The home profiles as a restore meets them: the one that is on ([activeId]), which it fills with the
 * backup, and the others ([deleted]), which it deletes: a backup file holds one profile. The one that is on
 * is also the one an export writes, under its stored name [activeName] (empty until the user names it): with
 * other profiles beside it the backup screen says which profile its file is of ([exportedProfileName]).
 *
 * [unlisted] are the ids something is still kept under though the list does not name them (a home screen's
 * rows, a profile's own values or picture; the list was lost with a damaged settings file, or a copy or a
 * restore died): a restore removes all of it, and the question says so. They have no names to show.
 */
data class RestoreProfiles(
    val activeId: Long,
    val deleted: List<HomeProfile>,
    val activeName: String = "",
    val unlisted: Set<Long> = emptySet(),
) {
    /** The names the question shows (an unnamed one is empty here; [restoreConfirmText] names it). */
    val names: List<String> get() = deleted.map { it.name }

    /** What the restore is held to once the question is answered: these profiles and these home screens, each
     *  as what the question said it was, or nothing is written. */
    val confirmed: ConfirmedProfiles get() = ConfirmedProfiles(activeId, deleted.map { it.id }.toSet(), unlisted)
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
    override fun profiles(): Flow<RestoreProfiles> =
        combine(profiles.profiles, profiles.activeId, profiles.unlistedHomeScreens, ::profilesARestoreDeletes)
}

/**
 * A restore's question on a screen that can ask it more than once (the backup screen stays open between two
 * restores): the profiles a restore would meet ([profiles]), and the restore that is held to them ([restore]).
 *
 * Part of what the question names is found from files (a profile's own store or picture kept under an id the
 * list does not name), and a file says nothing when it changes: the profiles are followed as the rows and the
 * list change, and nothing more. Read once when the screen opened, a listing that failed then left an id out
 * of every question this screen asked. The restore refused each time, as it must, and the user could not get
 * past it without leaving the screen. So the following is begun ANEW, which reads the files again, each time
 * the question is shown ([opened]) and after a restore that found the profiles changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RestoreQuestion(
    private val lost: ProfilesLostInRestore,
    private val restorer: BackupRestorer,
    scope: CoroutineScope,
) {
    /** Counts the times the profiles were asked to be read anew. */
    private val readings = MutableStateFlow(0)

    /** The home profiles a restore would meet now; null until they have been read. While they are read
     *  anew, the value of the reading before stands. */
    val profiles: StateFlow<RestoreProfiles?> =
        readings.flatMapLatest { lost.profiles() }.stateIn<RestoreProfiles?>(scope, SharingStarted.Eagerly, null)

    /** The question is about to be shown: what it names is read anew. */
    fun opened() = readAgain()

    /** Restores [uri], held to the profiles the question showed ([asked]). When they were no longer so, the
     *  next question is to name the profiles that restore met. */
    suspend fun restore(uri: Uri, asked: RestoreProfiles): BackupEvent {
        val event = restorer.restoreFrom(uri, asked.confirmed) {}
        if (event == BackupEvent.ProfilesChanged) readAgain()
        return event
    }

    private fun readAgain() = readings.update { it + 1 }
}

/** The one that is on ([activeId]) and every other profile of [profiles], in the list's order; and the home
 *  screens kept for profiles the list does not name ([unlisted]). */
internal fun profilesARestoreDeletes(profiles: List<HomeProfile>, activeId: Long, unlisted: Set<Long> = emptySet()): RestoreProfiles =
    RestoreProfiles(
        activeId,
        profiles.filter { it.id != activeId },
        profiles.firstOrNull { it.id == activeId }?.name.orEmpty(),
        unlisted - activeId - profiles.mapTo(HashSet()) { it.id },
    )

/**
 * What the restore's question says: what it always said, and which profiles go ([deletedProfiles]) if any do.
 * A profile without a name of its own (the first one, until the user names it) goes under the name it is
 * shown with everywhere, not under its empty stored one. With [unlistedHomeScreens] it also says that the home
 * screens no profile is listed for go: they have no names, and they went without a word before.
 */
internal fun restoreConfirmText(resources: Resources, deletedProfiles: List<String>, unlistedHomeScreens: Boolean = false): String {
    var question = resources.getString(R.string.backup_restore_confirm_msg)
    if (deletedProfiles.isNotEmpty()) {
        val names = deletedProfiles.joinToString(", ") { name ->
            name.ifBlank { resources.getString(org.arkikeskus.launcher.ui.R.string.profile_default_name) }
        }
        question += "\n\n" + resources.getString(R.string.backup_restore_confirm_profiles, names)
    }
    if (unlistedHomeScreens) question += "\n\n" + resources.getString(R.string.backup_restore_confirm_unlisted)
    return question
}

/** [restoreConfirmText] of what a restore would meet ([profiles]; null until they have been read). */
internal fun restoreConfirmText(resources: Resources, profiles: RestoreProfiles?): String =
    restoreConfirmText(resources, profiles?.names.orEmpty(), profiles?.unlisted?.isNotEmpty() == true)

/**
 * The backup screen's question before a restore. [profiles] is null until the profiles have been read: the
 * question cannot be answered with Restore before it can say what goes.
 *
 * Restore hands [onConfirm] the very profiles this question names. The caller used to read "the profiles now"
 * when the button was tapped: a profile made a moment before the tap was in that reading and not yet in the
 * text, so it was deleted without having been named, and the restore's own check passed (it was among the
 * confirmed ones). The text and the button take the one value of this composition.
 */
@Composable
internal fun RestoreConfirmDialog(profiles: RestoreProfiles?, onConfirm: (RestoreProfiles) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
        // It scrolls: with many profiles to name, on a small window at a large font, the text is taller
        // than the dialog, and a cut text let Restore be tapped without the last names having been seen.
        text = {
            Text(
                restoreConfirmText(LocalResources.current, profiles),
                Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { profiles?.let(onConfirm) }, enabled = profiles != null) {
                Text(stringResource(R.string.backup_restore_confirm_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        },
    )
}
