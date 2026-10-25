package org.arkikeskus.launcher.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.launcher.system.ShortSoundPlayer
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.ExpressiveCard
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** Which of the two sounds a choice is for. */
internal enum class SoundSlot { Lock, Unlock }

/** Where a sound comes from: a file (any common audio format), the phone's own sounds, or none. */
internal enum class SoundSource { File, Phone, None }

/**
 * Settings → Sounds: the lock and unlock sounds switch; while it is on, the sound for each — named,
 * with a play button once picked — and the way to the phone's sound settings, where its own screen
 * locking sound is turned off (an app can't).
 */
@Composable
internal fun LockSoundsSection(
    enabled: Boolean,
    lockUri: String,
    unlockUri: String,
    soundName: @Composable (String) -> String,
    onSetEnabled: (Boolean) -> Unit,
    onChoose: (SoundSlot, SoundSource) -> Unit,
    onPreview: (String) -> Unit,
    onOpenSoundSettings: () -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf<SoundSlot?>(null) }
    SwitchRow(
        stringResource(R.string.settings_lock_sounds),
        stringResource(R.string.settings_lock_sounds_desc),
        enabled,
        onSetEnabled,
    )
    if (!enabled) return
    SoundRow(stringResource(R.string.settings_lock_sound), lockUri, soundName, onPick = { choosing = SoundSlot.Lock }, onPreview)
    SoundRow(stringResource(R.string.settings_unlock_sound), unlockUri, soundName, onPick = { choosing = SoundSlot.Unlock }, onPreview)
    ExpressiveActionRow(
        label = stringResource(R.string.settings_phone_sound_settings),
        description = stringResource(R.string.settings_phone_sound_settings_desc),
        trailingIcon = LauncherIcons.OpenInNew,
        onClick = onOpenSoundSettings,
    )
    choosing?.let { slot ->
        SoundSourceDialog(
            title = stringResource(if (slot == SoundSlot.Lock) R.string.settings_lock_sound else R.string.settings_unlock_sound),
            onPick = { source ->
                choosing = null
                onChoose(slot, source)
            },
            onDismiss = { choosing = null },
        )
    }
}

@Composable
private fun SoundRow(
    label: String,
    uri: String,
    soundName: @Composable (String) -> String,
    onPick: () -> Unit,
    onPreview: (String) -> Unit,
) {
    val p = LocalExpressivePalette.current
    ExpressiveCard(onClick = onPick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(
                if (uri.isBlank()) stringResource(R.string.settings_sound_none) else soundName(uri),
                color = p.dim,
                fontSize = 12.5.sp,
            )
        }
        if (uri.isNotBlank()) {
            IconButton(onClick = { onPreview(uri) }) {
                Icon(
                    painter = painterResource(LauncherIcons.Play),
                    contentDescription = stringResource(R.string.settings_sound_play),
                    tint = Accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun SoundSourceDialog(title: String, onPick: (SoundSource) -> Unit, onDismiss: () -> Unit) {
    val p = LocalExpressivePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceHi,
        title = { Text(title, color = p.text) },
        text = {
            Column {
                for ((source, label) in listOf(
                    SoundSource.File to R.string.settings_sound_from_files,
                    SoundSource.Phone to R.string.settings_sound_from_phone,
                    SoundSource.None to R.string.settings_sound_none,
                )) {
                    Text(
                        stringResource(label),
                        color = p.text,
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(source) }.padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close), color = Accent) }
        },
    )
}

/**
 * [LockSoundsSection] wired to the phone: the file picker (any audio type, kept readable across
 * restarts with a persistable grant), the phone's sound picker (its ringtones, notification and alarm
 * sounds; long ones are cut short when played), a preview player, and the system sound settings.
 */
@Composable
internal fun LockSoundsSettings(
    enabled: Boolean,
    lockUri: String,
    unlockUri: String,
    onSetEnabled: (Boolean) -> Unit,
    onSetSound: (SoundSlot, String) -> Unit,
) {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<SoundSlot?>(null) }
    val current: (SoundSlot) -> String = { if (it == SoundSlot.Lock) lockUri else unlockUri }
    val set: (SoundSlot, String) -> Unit = { slot, uri ->
        releaseFileGrant(context, current(slot), keep = uri)
        onSetSound(slot, uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        set(slot, uri.toString())
    }
    val phonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val slot = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
        if (uri != null) set(slot, uri.toString())
    }
    val player = remember(context) { ShortSoundPlayer(context) }
    DisposableEffect(player) { onDispose { player.stop() } }
    val phoneTitle = stringResource(R.string.settings_sound_from_phone)
    LockSoundsSection(
        enabled = enabled,
        lockUri = lockUri,
        unlockUri = unlockUri,
        soundName = { uri -> rememberSoundName(uri) },
        onSetEnabled = onSetEnabled,
        onChoose = { slot, source ->
            when (source) {
                SoundSource.None -> set(slot, "")
                SoundSource.File -> {
                    pending = slot
                    runCatching { filePicker.launch(arrayOf("audio/*")) }.onFailure { pending = null }
                }
                SoundSource.Phone -> {
                    pending = slot
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, phoneTitle)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current(slot).takeIf { it.isNotBlank() }?.toUri())
                    runCatching { phonePicker.launch(intent) }.onFailure { pending = null }
                }
            }
        },
        onPreview = { player.play(it.toUri()) { true } },
        onOpenSoundSettings = {
            try {
                context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        },
    )
}

/** A picked sound's name: the phone's own title for its sounds, the file name for a file. Read from
 *  the provider: `RingtoneManager.getRingtone` would set up a media player just for the title. */
@Composable
private fun rememberSoundName(uri: String): String {
    val context = LocalContext.current
    val fallback = stringResource(R.string.settings_sound_picked)
    val name by produceState<String?>(null, uri) {
        value = withContext(Dispatchers.IO) { soundDisplayName(context, uri.toUri()) }
    }
    return name ?: fallback
}

private fun soundDisplayName(context: Context, uri: Uri): String? {
    val column = if (uri.authority == MediaStore.AUTHORITY) MediaStore.MediaColumns.TITLE else OpenableColumns.DISPLAY_NAME
    return runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** Gives back the read grant of a picked file that is being replaced, so grants don't pile up. */
private fun releaseFileGrant(context: Context, old: String, keep: String) {
    if (old.isBlank() || old == keep) return
    val uri = old.toUri()
    val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    if (held) runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}
