package org.arkikeskus.launcher.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The backup file another app hands over with "Open with" (`ACTION_VIEW`) or share (`ACTION_SEND`),
 * or null. The import is an exported activity any app can start, so only a content URI from another
 * app's provider is taken: never a file path (the launcher has no storage access), and never one of
 * the launcher's own providers, which another app must not get it to read for them. The provider is
 * looked up the way ContentResolver finds it: the decoded authority after its last `@` (a `userId@`
 * prefix, written plainly or as `%40`, would otherwise hide an own authority from the check).
 */
fun backupImportUri(context: Context, intent: Intent): Uri? {
    val uri = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    } ?: return null
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val authority = uri.authority?.substringAfterLast('@') ?: return null
    val owner = context.packageManager.resolveContentProvider(authority, 0)?.packageName
    return uri.takeIf { owner != context.packageName }
}

/** Where the "Open with" import is: asking, reading the file, replacing the layout, or showing how it went. */
sealed interface BackupImportState {
    data object Confirm : BackupImportState

    /** The file is being read and checked: nothing is changed yet, and the user may still leave. */
    data object Reading : BackupImportState

    /** The layout is being replaced: a short step that always finishes, so there is no way out. */
    data object Restoring : BackupImportState
    data class Done(val event: BackupEvent) : BackupImportState

    /** The user left before anything was changed; the screen is going away and shows nothing. */
    data object Left : BackupImportState
}

@HiltViewModel
class BackupImportViewModel @Inject constructor(private val restorer: BackupRestorer) : ViewModel() {
    private val _state = MutableStateFlow<BackupImportState>(BackupImportState.Confirm)
    val state: StateFlow<BackupImportState> = _state.asStateFlow()
    private var job: Job? = null

    /** Restores [uri] once: a second tap, or one after the outcome, does nothing. The state lives here,
     *  so a rotation mid-restore shows the same step again instead of asking anew. */
    fun restore(uri: Uri) {
        if (_state.value != BackupImportState.Confirm) return
        _state.value = BackupImportState.Reading
        job = viewModelScope.launch {
            val event = restorer.restoreFrom(uri) { _state.value = BackupImportState.Restoring }
            _state.value = BackupImportState.Done(event)
        }
    }

    /** Whether the user may leave now. Leaving while the file is still read stops the import (a
     *  provider that never answers must not trap the user); while the layout is replaced it is refused. */
    fun leave(): Boolean = when (_state.value) {
        BackupImportState.Restoring -> false
        BackupImportState.Reading, BackupImportState.Confirm -> {
            job?.cancel()
            // Nothing may start after this, not even a Restore tap that lands late.
            _state.value = BackupImportState.Left
            true
        }
        is BackupImportState.Done, BackupImportState.Left -> true
    }
}

/** The "Open with" import of [uri]: the confirmation, the restore and its outcome. */
@Composable
fun BackupImportScreen(
    uri: Uri,
    onFinish: () -> Unit,
    viewModel: BackupImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackupImportDialog(
        state = state,
        onRestore = { viewModel.restore(uri) },
        onLeave = { if (viewModel.leave()) onFinish() },
    )
}

@Composable
internal fun BackupImportDialog(
    state: BackupImportState,
    onRestore: () -> Unit,
    onLeave: () -> Unit,
) {
    when (state) {
        BackupImportState.Confirm -> AlertDialog(
            onDismissRequest = onLeave,
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { DialogText(stringResource(R.string.backup_restore_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = onRestore) { Text(stringResource(R.string.backup_restore_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = onLeave) { Text(stringResource(R.string.backup_cancel)) }
            },
        )
        // Back, a tap outside and Cancel stop the import: the file may never arrive.
        BackupImportState.Reading -> AlertDialog(
            onDismissRequest = onLeave,
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Working() },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onLeave) { Text(stringResource(R.string.backup_cancel)) }
            },
        )
        // No way out while the layout is replaced: the step is short and always finishes.
        BackupImportState.Restoring -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Working() },
            confirmButton = {},
        )
        is BackupImportState.Done -> AlertDialog(
            onDismissRequest = onLeave,
            title = { Text(stringResource(R.string.backup_title)) },
            text = { DialogText(backupEventMessage(LocalResources.current, state.event)) },
            confirmButton = {
                TextButton(onClick = onLeave) { Text(stringResource(android.R.string.ok)) }
            },
        )
        BackupImportState.Left -> Unit
    }
}

@Composable
private fun Working() {
    FilterObscuredTouches()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(stringResource(R.string.backup_restoring))
    }
}

/** A dialog's message, scrollable: on a small screen at a large font the blocked-read advice is taller
 *  than the dialog allows, and its end is the part that says what to do instead. */
@Composable
private fun DialogText(text: String) {
    FilterObscuredTouches()
    Text(text, Modifier.verticalScroll(rememberScrollState()))
}

/** Drops touches that reach the dialog's window through another app's overlay (tapjacking): the
 *  dialog replaces the layout on one tap, over the app that handed the file. Every Android version;
 *  from Android 12 the activity also hides such overlays outright. */
@Composable
private fun FilterObscuredTouches() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.rootView.filterTouchesWhenObscured = true
        onDispose {}
    }
}
