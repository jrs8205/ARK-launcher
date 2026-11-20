package org.arkikeskus.launcher.feature.backup

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }

    // --- File-import state (rememberSaveable: the confirm dialog must survive rotation) ---
    var pendingImport by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val lastBackup by viewModel.lastBackup.collectAsStateWithLifecycle()

    // --- File launchers ---
    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImport = uri }

    // A ROM without a documents UI has no handler for the pickers: launching one throws, and the
    // backup screen runs in the home process. Say so instead.
    val scope = rememberCoroutineScope()
    val launchFailed = stringResource(R.string.backup_failed)
    fun guarded(launch: () -> Unit) {
        runCatching(launch).onFailure { scope.launch { snackbar.showSnackbar(launchFailed) } }
    }

    // --- Event handler ---
    val resources by rememberUpdatedState(LocalResources.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        viewModel.events.flowWithLifecycle(lifecycle).collectLatest { e ->
            val message = backupEventMessage(resources, e)
            if (e.needsTimeToRead) {
                snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
            } else {
                snackbar.showSnackbar(message)
            }
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
            // A file holds the one profile that is on: with several profiles the button and the time below
            // say which (ExportTexts).
            val exportProfiles by viewModel.restoreProfiles.collectAsStateWithLifecycle()
            ExportButton(exportProfiles, guarded = ::guarded, onExport = viewModel::exportTo)
            OutlinedButton(
                onClick = { guarded { openDoc.launch(arrayOf("application/json")) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_import)) }
            // The time and the profile it is of are one value; the line shows only while that profile is the
            // one the button names (ExportTexts).
            lastBackupText(resources, exportProfiles, lastBackup) { timeMs ->
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(timeMs))
            }?.let { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }

    // --- File import confirm dialog ---
    pendingImport?.let { uri ->
        val profiles by viewModel.restoreProfiles.collectAsStateWithLifecycle()
        RestoreConfirmDialog(
            profiles = profiles,
            // The profiles the question names are the ones the restore is held to: the dialog hands them over.
            onConfirm = { shown -> viewModel.importFrom(uri, shown); pendingImport = null },
            onDismiss = { pendingImport = null },
        )
    }
}

/** The suggested export name: [base] plus when the backup was made, so a new export never offers
 *  the previous one's name. ASCII digits in every locale, and no characters a file system rejects. */
internal fun backupFileName(base: String, now: LocalDateTime): String =
    "$base-${now.format(BACKUP_NAME_TIME)}.json"

/**
 * The export button with the system's "create a file" picker behind it. An export is for the profile the
 * button NAMES WHEN IT IS TAPPED ([profiles]' active one): that id is kept across the picker, and across a
 * recreation of the screen under it, and goes to [onExport] with the file, whatever profile is on when the
 * picker answers. The export itself refuses to write another profile than this one. Not enabled before the
 * profiles are read: there would be no profile to hold the export to. [guarded] runs the launch of the
 * picker (a phone without one must not take the screen down).
 */
@Composable
internal fun ExportButton(profiles: RestoreProfiles?, guarded: (() -> Unit) -> Unit, onExport: (android.net.Uri, Long) -> Unit) {
    var exportFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> exportFor?.let { profile -> if (uri != null) onExport(uri, profile) } }
    val defaultName = stringResource(R.string.backup_default_name)
    Button(
        onClick = {
            exportFor = profiles?.activeId
            guarded { createDoc.launch(backupFileName(defaultName, LocalDateTime.now())) }
        },
        enabled = profiles != null,
        modifier = Modifier.fillMaxWidth().testTag(EXPORT_BUTTON_TAG),
    ) { Text(exportButtonText(LocalResources.current, profiles)) }
}

/** The export button, for tests. */
internal const val EXPORT_BUTTON_TAG = "backup-export"

private val BACKUP_NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm", Locale.ROOT)
