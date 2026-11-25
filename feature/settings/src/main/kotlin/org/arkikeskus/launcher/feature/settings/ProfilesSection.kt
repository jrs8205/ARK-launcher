package org.arkikeskus.launcher.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.model.HomeProfile
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.expressive.expressiveCardGap
import org.arkikeskus.launcher.ui.expressive.expressiveCardShadow
import org.arkikeskus.launcher.ui.expressive.expressiveCardShape

/** The tag of the row of the home profile [id] on the Profiles page. */
internal fun profileRowTag(id: Long): String = "profile-row-$id"

/** The tag of the "New profile" button. */
internal const val NEW_PROFILE_TAG = "profile-new"

/** The tag of the line that says what the mark on a row means. */
internal const val PROFILE_MARK_LEGEND_TAG = "profile-mark-legend"

/**
 * True while the launcher has two or more home profiles: Settings then says which one is being edited, and a
 * row whose setting each profile has of its own ([SettingEntry.perProfile]) is marked. With one profile nothing
 * of this shows.
 */
internal val LocalHomeProfileRowMarks = compositionLocalOf { false }

/** The name [profile] goes by: its own, or for a first profile nobody has named yet, the default one. */
@Composable
internal fun homeProfileName(profile: HomeProfile): String =
    profile.name.ifBlank { stringResource(org.arkikeskus.launcher.ui.R.string.profile_default_name) }

/**
 * The Profiles page: the home profiles in their order, the one in use marked; a tap on another switches to it
 * at once, the pencil beside each renames or deletes it (asked first; the last profile cannot be deleted), and
 * "New profile" makes one, as a copy of the one in use or empty, until there are [HomeProfile.MAX_PROFILES].
 */
@Composable
internal fun ProfilesRows(s: LauncherSettings, lists: SettingsLists, actions: SettingsActions) {
    val p = LocalExpressivePalette.current
    val profiles = lists.homeProfiles
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }

    SettingRow("PF1", grouped = false) {
        Text(
            text = stringResource(R.string.settings_profile_list),
            color = p.dim,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 8.dp),
        )
        // Cards of their own, not rows of the page's group: each has two things to press.
        profiles.forEach { profile ->
            ProfileCard(
                profile = profile,
                inUse = profile.id == s.homeProfileId,
                onSwitch = { actions.switchHomeProfile(profile.id) },
                onEdit = { editingId = profile.id },
            )
        }
    }
    SettingRow("PF2", grouped = false) {
        NewProfileCard(full = profiles.size >= HomeProfile.MAX_PROFILES, onClick = { creating = true })
    }

    if (creating) {
        NewProfileDialog(
            onCreate = { name, copy ->
                creating = false
                // "A copy of the one in use" is the profile this page shows as in use: named, so that the copy
                // is of it whichever profile is on when the making gets its turn.
                actions.createHomeProfile(name, if (copy) s.homeProfileId else null)
            },
            onDismiss = { creating = false },
        )
    }
    profiles.firstOrNull { it.id == editingId }?.let { profile ->
        EditProfileDialog(
            profile = profile,
            canDelete = profiles.size > 1,
            onRename = { name ->
                editingId = null
                actions.renameHomeProfile(profile.id, name)
            },
            onDelete = {
                editingId = null
                deletingId = profile.id
            },
            onDismiss = { editingId = null },
        )
    }
    profiles.firstOrNull { it.id == deletingId }?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.settings_profile_delete)) },
            text = { Text(stringResource(R.string.settings_profile_delete_confirm, homeProfileName(profile))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingId = null
                        actions.deleteHomeProfile(profile.id)
                    },
                ) { Text(stringResource(R.string.settings_folder_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.settings_folder_cancel)) }
            },
        )
    }
}

/** One profile: the whole row left of the pencil is the choice (a radio button to a screen reader). */
@Composable
private fun ProfileCard(profile: HomeProfile, inUse: Boolean, onSwitch: () -> Unit, onEdit: () -> Unit) {
    val p = LocalExpressivePalette.current
    val name = homeProfileName(profile)
    Surface(
        color = p.surfaceHi,
        shape = expressiveCardShape(),
        shadowElevation = expressiveCardShadow(),
        modifier = Modifier.fillMaxWidth().padding(bottom = expressiveCardGap()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .testTag(profileRowTag(profile.id))
                    // The profile in use is already chosen: a tap on it asks for nothing.
                    .selectable(selected = inUse, role = Role.RadioButton, onClick = { if (!inUse) onSwitch() })
                    .heightIn(min = 56.dp)
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The radio mark: a ring, filled for the one in use.
                Box(
                    modifier = Modifier.size(20.dp).border(2.dp, if (inUse) p.fill else p.faint, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (inUse) Box(Modifier.size(10.dp).background(p.fill, CircleShape))
                }
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(name, color = p.text, fontSize = 16.sp)
                    if (inUse) Text(stringResource(R.string.settings_profile_active), color = p.dim, fontSize = 12.5.sp)
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.padding(end = 6.dp)) {
                Icon(
                    painter = painterResource(LauncherIcons.Edit),
                    contentDescription = stringResource(R.string.settings_profile_edit, name),
                    tint = p.dim,
                )
            }
        }
    }
}

/** "New profile"; at the limit it says so and is disabled. */
@Composable
private fun NewProfileCard(full: Boolean, onClick: () -> Unit) {
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = expressiveCardShape(),
        shadowElevation = expressiveCardShadow(),
        modifier = Modifier.fillMaxWidth().padding(bottom = expressiveCardGap()),
    ) {
        Row(
            modifier = Modifier
                .testTag(NEW_PROFILE_TAG)
                .clickable(enabled = !full, role = Role.Button, onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_profile_new),
                    color = if (full) p.dim else p.accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (full) Text(stringResource(R.string.settings_profile_limit), color = p.dim, fontSize = 12.5.sp)
            }
            Icon(
                painter = painterResource(LauncherIcons.Add),
                contentDescription = null,
                tint = if (full) p.faint else p.accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** The name of the new profile, whether it starts as a copy of the one in use, and what a profile is. */
@Composable
private fun NewProfileDialog(onCreate: (name: String, copyCurrent: Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var copy by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_profile_new)) },
        text = {
            Column {
                ProfileNameField(name) { name = it }
                StartChoice(stringResource(R.string.settings_profile_copy_current), selected = copy) { copy = true }
                StartChoice(stringResource(R.string.settings_profile_empty), selected = !copy) { copy = false }
                // So nobody takes this for Android's work profile, or thinks a left-out app is stopped.
                Text(
                    text = stringResource(R.string.settings_profile_explain),
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(HomeProfile.cleanName(name), copy) }) {
                Text(stringResource(R.string.settings_profile_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_folder_cancel)) } },
    )
}

/** A profile's name, and the way to delete it ([canDelete] is false for the last profile). */
@Composable
private fun EditProfileDialog(
    profile: HomeProfile,
    canDelete: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(homeProfileName(profile)) },
        text = {
            Column {
                ProfileNameField(name) { name = it }
                if (canDelete) {
                    TextButton(onClick = onDelete, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.settings_profile_delete))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && HomeProfile.cleanName(name) != profile.name,
                onClick = { onRename(HomeProfile.cleanName(name)) },
            ) { Text(stringResource(R.string.settings_people_batch_times_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_folder_cancel)) } },
    )
}

@Composable
private fun ProfileNameField(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        // On one line and no longer than a name is kept, cut where a character ends as the kept name is
        // (HomeProfile.fitName): never half an emoji in the field.
        onValueChange = { onChange(HomeProfile.fitName(it.replace('\n', ' '))) },
        singleLine = true,
        label = { Text(stringResource(R.string.settings_profile_name_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One of the two ways a new profile starts: a radio button with its text, the whole line the target. */
@Composable
private fun StartChoice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, modifier = Modifier.padding(start = 12.dp))
    }
}

/** What the mark on a row means, written out on a page that has such a row. */
@Composable
internal fun ProfileMarkLegend(modifier: Modifier = Modifier) {
    val p = LocalExpressivePalette.current
    Row(
        modifier = modifier.testTag(PROFILE_MARK_LEGEND_TAG).padding(start = 22.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(LauncherIcons.Person), contentDescription = null, tint = p.dim, modifier = Modifier.size(13.dp))
        Text(
            text = stringResource(R.string.settings_profile_row_mark),
            color = p.dim,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
