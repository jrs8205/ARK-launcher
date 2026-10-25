package org.arkikeskus.launcher.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The backup file another app hands over with "Open with" (`ACTION_VIEW`) or share (`ACTION_SEND`),
 * or null. The import is an exported activity any app can start, so only a content URI from another
 * app's provider is taken: never a file path (the launcher has no storage access), and never one of
 * the launcher's own providers, which another app must not get it to read for them. [Uri.getHost]
 * leaves out a `userId@` prefix, which would otherwise hide an own authority from the check.
 */
fun backupImportUri(context: Context, intent: Intent): Uri? {
    val uri = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    } ?: return null
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val authority = uri.host ?: return null
    val owner = context.packageManager.resolveContentProvider(authority, 0)?.packageName
    return uri.takeIf { owner != context.packageName }
}

/** Where the "Open with" import is: asking, restoring, or showing how it went. */
sealed interface BackupImportState {
    data object Confirm : BackupImportState
    data object Running : BackupImportState
    data class Done(val event: BackupEvent) : BackupImportState
}

@HiltViewModel
class BackupImportViewModel @Inject constructor(private val restorer: BackupRestorer) : ViewModel() {
    private val _state = MutableStateFlow<BackupImportState>(BackupImportState.Confirm)
    val state: StateFlow<BackupImportState> = _state.asStateFlow()

    /** Restores [uri] once: a second tap, or one after the outcome, does nothing. The state lives here,
     *  so a rotation mid-restore shows the same step again instead of asking anew. */
    fun restore(uri: Uri) {
        if (_state.value != BackupImportState.Confirm) return
        _state.value = BackupImportState.Running
        viewModelScope.launch { _state.value = BackupImportState.Done(restorer.restoreFrom(uri)) }
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
    BackupImportDialog(state = state, onRestore = { viewModel.restore(uri) }, onFinish = onFinish)
}

@Composable
internal fun BackupImportDialog(
    state: BackupImportState,
    onRestore: () -> Unit,
    onFinish: () -> Unit,
) {
    when (state) {
        BackupImportState.Confirm -> AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_restore_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = onRestore) { Text(stringResource(R.string.backup_restore_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.backup_cancel)) }
            },
        )
        // No way out while the layout is being replaced: leaving would cancel the restore half-way.
        BackupImportState.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.backup_restoring))
                }
            },
            confirmButton = {},
        )
        is BackupImportState.Done -> AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(stringResource(R.string.backup_title)) },
            text = { Text(backupEventMessage(LocalResources.current, state.event)) },
            confirmButton = {
                TextButton(onClick = onFinish) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}
