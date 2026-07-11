package org.arkikeskus.launcher.feature.backup

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.feature.backup.drive.DriveAuth
import org.arkikeskus.launcher.feature.backup.drive.DriveFile

/** Actions that can be queued while waiting for Drive consent resolution. */
private sealed interface DriveAction {
    data object Backup : DriveAction
    data object List : DriveAction
    /** Turning the auto-backup switch on: authorize first, only then persist the flag. */
    data object Enable : DriveAction
    data class Restore(val fileId: String) : DriveAction
}

/** DriveAction encoded as a string so a pending consent round-trip survives rotation. */
private val DriveActionSaver = Saver<DriveAction?, String>(
    save = {
        when (it) {
            DriveAction.Backup -> "backup"
            DriveAction.List -> "list"
            DriveAction.Enable -> "enable"
            is DriveAction.Restore -> "restore:${it.fileId}"
            null -> ""
        }
    },
    restore = {
        when {
            it == "backup" -> DriveAction.Backup
            it == "list" -> DriveAction.List
            it == "enable" -> DriveAction.Enable
            it.startsWith("restore:") -> DriveAction.Restore(it.removePrefix("restore:"))
            else -> null
        }
    },
)

private val DriveFileSaver = listSaver<DriveFile?, String>(
    save = { if (it == null) emptyList() else listOf(it.id, it.name, it.modifiedTime) },
    restore = { if (it.isEmpty()) null else DriveFile(it[0], it[1], it[2]) },
)

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val activity = context as Activity
    val snackbar = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // --- File-import state (rememberSaveable: the confirm dialog must survive rotation) ---
    var pendingImport by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val localLastBackupMs by viewModel.localLastBackupMs.collectAsStateWithLifecycle()

    // --- Drive state ---
    val driveState by viewModel.driveState.collectAsStateWithLifecycle()
    /**
     * Access token cached within the screen's lifetime.
     * Identity's auth client caches tokens server-side; caching here avoids redundant auth calls
     * within a single screen session. Deliberately NOT saved across rotation: the token is
     * short-lived and a silent re-auth replaces it.
     */
    var cachedToken by remember { mutableStateOf<String?>(null) }
    /** The Drive action waiting for the consent-resolution result (saved: the consent dialog is a
     *  separate activity, and rotating behind it must not orphan its result). */
    var pendingDriveAction by rememberSaveable(stateSaver = DriveActionSaver) { mutableStateOf<DriveAction?>(null) }
    var showDriveFiles by rememberSaveable { mutableStateOf(false) }
    var pendingDriveRestore by rememberSaveable(stateSaver = DriveFileSaver) { mutableStateOf<DriveFile?>(null) }

    /**
     * Dispatches a resolved Drive action to the ViewModel.
     * Must only be called after a valid [token] has been obtained.
     */
    fun dispatchDriveAction(action: DriveAction, token: String) {
        when (action) {
            DriveAction.Backup -> viewModel.backupToDrive(token)
            DriveAction.List -> {
                viewModel.listDriveBackups(token)
                showDriveFiles = true
            }
            // Reaching here proves authorization succeeded — only now persist the enable flag.
            DriveAction.Enable -> viewModel.setDriveEnabled(true)
            is DriveAction.Restore -> viewModel.restoreFromDrive(token, action.fileId)
        }
    }

    /**
     * Launcher for the Drive consent resolution intent (the "hasResolution" path from
     * [DriveAuth.authorizeOrNull]).  After the user grants consent, reads the access token
     * from the result intent and re-dispatches the queued [pendingDriveAction].
     *
     * NOTE (device-verify): [Identity.getAuthorizationClient].getAuthorizationResultFromIntent
     * returns an [AuthorizationResult] whose [accessToken] may still be null if the user denied
     * consent.  The null-safe `getOrNull()` guard handles that case by emitting [AuthFailed].
     */
    val driveResolver = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val token = runCatching {
            Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(result.data).accessToken
        }.getOrNull()
        if (token == null) {
            viewModel.emitAuthFailed()
            pendingDriveAction = null
            return@rememberLauncherForActivityResult
        }
        cachedToken = token
        val action = pendingDriveAction ?: return@rememberLauncherForActivityResult
        pendingDriveAction = null
        dispatchDriveAction(action, token)
    }

    /**
     * Authorizes with Drive (or uses the cached token) then dispatches [action].
     * If the authorization result requires a resolution UI ([hasResolution] == true), stores
     * [action] in [pendingDriveAction] and launches the consent intent; the [driveResolver]
     * callback will re-dispatch once consent is granted.
     */
    fun launchDriveOp(action: DriveAction) {
        coroutineScope.launch {
            val existing = cachedToken
            if (existing != null) {
                dispatchDriveAction(action, existing)
                return@launch
            }
            val result = runCatching { DriveAuth(activity).authorizeOrNull() }.getOrElse { err ->
                if (err is kotlinx.coroutines.CancellationException) throw err
                viewModel.emitAuthFailed()
                return@launch
            }
            if (result.hasResolution()) {
                pendingDriveAction = action
                val sender = result.pendingIntent?.intentSender
                if (sender == null) { viewModel.emitAuthFailed(); return@launch }
                driveResolver.launch(
                    IntentSenderRequest.Builder(sender).build(),
                )
            } else {
                val token = result.accessToken ?: run { viewModel.emitAuthFailed(); return@launch }
                cachedToken = token
                dispatchDriveAction(action, token)
            }
        }
    }

    // --- File launchers ---
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImport = uri }

    // --- Event handler ---
    val restoredMsg = stringResource(R.string.backup_restored)
    val invalidMsg = stringResource(R.string.backup_import_invalid)
    val tooLargeMsg = stringResource(R.string.backup_error_too_large)
    val failedMsg = stringResource(R.string.backup_failed)
    val exportedMsg = stringResource(R.string.backup_exported)
    val authFailedMsg = stringResource(R.string.backup_auth_failed)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        viewModel.events.flowWithLifecycle(lifecycle).collectLatest { e ->
            // Clear the cached token on any Drive failure so the next action re-authorizes
            // instead of reusing an expired token (tokens last ~1 h).
            if (e is BackupEvent.Failed || e == BackupEvent.AuthFailed) cachedToken = null
            snackbar.showMessage(
                when (e) {
                    is BackupEvent.Exported -> exportedMsg
                    is BackupEvent.Restored ->
                        if (e.skipped > 0) {
                            context.resources.getQuantityString(
                                R.plurals.backup_restored_skipped, e.skipped, e.skipped,
                            )
                        } else {
                            restoredMsg
                        }
                    BackupEvent.InvalidFile -> invalidMsg
                    BackupEvent.TooLarge -> tooLargeMsg
                    is BackupEvent.Failed -> if (e.message.isBlank()) failedMsg else "$failedMsg: ${e.message}"
                    BackupEvent.DriveUploaded -> exportedMsg
                    BackupEvent.AuthFailed -> authFailedMsg
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

            // --- File section ---
            Text(stringResource(R.string.backup_file_section), style = MaterialTheme.typography.titleMedium)
            val backupDefaultName = stringResource(R.string.backup_default_name)
            Button(
                onClick = { createDoc.launch("$backupDefaultName.json") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(
                onClick = { openDoc.launch(arrayOf("application/json")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_import)) }
            if (localLastBackupMs > 0L) {
                val formatted = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT,
                ).format(java.util.Date(localLastBackupMs))
                Text(
                    stringResource(R.string.backup_last_time, formatted),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // --- Drive section ---
            Text(stringResource(R.string.backup_drive_section), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.backup_daily_toggle))
                Switch(
                    checked = driveState.enabled,
                    onCheckedChange = { checked ->
                        // Enabling goes through the auth path first: a failed or cancelled consent
                        // leaves the flag off (with the auth-failed snackbar) instead of arming a
                        // worker that can never upload.
                        if (checked) launchDriveOp(DriveAction.Enable) else viewModel.setDriveEnabled(false)
                    },
                )
            }
            if (driveState.lastBackupMs > 0L) {
                val formatted = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT,
                ).format(java.util.Date(driveState.lastBackupMs))
                Text(
                    stringResource(R.string.backup_last_time, formatted),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (driveState.enabled) {
                if (driveState.failing) {
                    Text(
                        stringResource(R.string.backup_drive_failing_banner),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { launchDriveOp(DriveAction.Backup) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !driveState.isLoading,
                ) { Text(stringResource(R.string.backup_now)) }
                OutlinedButton(
                    onClick = { launchDriveOp(DriveAction.List) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !driveState.isLoading,
                ) { Text(stringResource(R.string.backup_restore_drive)) }

                // --- Drive scheduling options ---
                Text(stringResource(R.string.backup_frequency), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = driveState.intervalDays < 7,
                        onClick = { viewModel.setDriveIntervalDays(1) },
                        label = { Text(stringResource(R.string.backup_freq_daily)) },
                    )
                    FilterChip(
                        selected = driveState.intervalDays >= 7,
                        onClick = { viewModel.setDriveIntervalDays(7) },
                        label = { Text(stringResource(R.string.backup_freq_weekly)) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.backup_wifi_only))
                    Switch(
                        checked = driveState.wifiOnly,
                        onCheckedChange = { viewModel.setDriveWifiOnly(it) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.backup_charging_only))
                    Switch(
                        checked = driveState.chargingOnly,
                        onCheckedChange = { viewModel.setDriveChargingOnly(it) },
                    )
                }
            }
        }
    }

    // --- File import confirm dialog ---
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

    // --- Drive files list dialog ---
    if (showDriveFiles) {
        AlertDialog(
            onDismissRequest = { showDriveFiles = false },
            title = { Text(stringResource(R.string.backup_restore_drive)) },
            text = {
                when {
                    driveState.isLoading -> CircularProgressIndicator()
                    driveState.availableFiles.isEmpty() ->
                        Text(stringResource(R.string.backup_no_backups))
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
                        // The list is newest-first (Drive orderBy modifiedTime desc). Show each backup's
                        // date/time (readable, and works for old millisecond-named files too) and tag the
                        // newest, so the user can tell which is the latest.
                        itemsIndexed(driveState.availableFiles) { index, file ->
                            val whenLabel = remember(file.modifiedTime) {
                                runCatching {
                                    java.time.format.DateTimeFormatter
                                        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                                        .withZone(java.time.ZoneId.systemDefault())
                                        .format(java.time.Instant.parse(file.modifiedTime))
                                }.getOrDefault(file.name)
                            }
                            val label = if (index == 0) {
                                "$whenLabel  •  ${stringResource(R.string.backup_latest)}"
                            } else {
                                whenLabel
                            }
                            TextButton(
                                onClick = { pendingDriveRestore = file; showDriveFiles = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDriveFiles = false }) { Text(stringResource(R.string.backup_cancel)) }
            },
        )
    }

    // --- Drive restore confirm dialog ---
    pendingDriveRestore?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDriveRestore = null },
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_restore_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    val fileId = file.id
                    pendingDriveRestore = null
                    launchDriveOp(DriveAction.Restore(fileId))
                }) { Text(stringResource(R.string.backup_restore_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDriveRestore = null }) { Text(stringResource(R.string.backup_cancel)) }
            },
        )
    }
}

private suspend fun SnackbarHostState.showMessage(msg: String) { showSnackbar(msg) }
