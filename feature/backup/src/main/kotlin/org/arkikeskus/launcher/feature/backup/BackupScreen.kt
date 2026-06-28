package org.arkikeskus.launcher.feature.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.flow.collectLatest

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImport = uri }

    val restoredMsg = stringResource(R.string.backup_restored)
    val invalidMsg = stringResource(R.string.backup_import_invalid)
    val failedMsg = stringResource(R.string.backup_failed)
    val exportedMsg = stringResource(R.string.backup_exported)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        viewModel.events.flowWithLifecycle(lifecycle).collectLatest { e ->
            snackbar.showMessage(
                when (e) {
                    is BackupEvent.Exported -> exportedMsg
                    is BackupEvent.Restored -> String.format(restoredMsg, e.restored, e.skipped)
                    BackupEvent.InvalidFile -> invalidMsg
                    is BackupEvent.Failed -> failedMsg
                },
            )
        }
    }

    Scaffold(modifier = modifier.fillMaxSize(), snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.backup_file_section), style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { createDoc.launch(context.getString(R.string.backup_default_name) + ".json") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(
                onClick = { openDoc.launch(arrayOf("application/json")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_import)) }
        }
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_restore_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = { viewModel.importFrom(uri); pendingImport = null }) {
                    Text(stringResource(R.string.backup_restore_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.backup_cancel)) }
            },
        )
    }
}

private suspend fun SnackbarHostState.showMessage(msg: String) { showSnackbar(msg) }
