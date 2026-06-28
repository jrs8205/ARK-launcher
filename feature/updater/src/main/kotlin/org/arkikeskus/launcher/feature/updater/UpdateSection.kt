package org.arkikeskus.launcher.feature.updater

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun UpdateSection(modifier: Modifier = Modifier, viewModel: UpdateViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Request POST_NOTIFICATIONS (API 33+) when the user enables auto-update. If denied the
    // feature still works (in-app card); only the background push is skipped.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result intentionally ignored — push is optional */ }
    // Fix 1: also request on first composition so auto_update_enabled=true (default) never
    // silently misses the permission prompt on Android 13+.
    LaunchedEffect(s.isReleaseBuild) {
        if (s.isReleaseBuild &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_section), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.update_current_version, s.currentVersion),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (s.isReleaseBuild) {
            s.available?.let { info ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.update_available_title, info.versionName),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.labelLarge)
                        Text(info.notes, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { viewModel.installUpdate(info) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_install))
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.update_auto_toggle))
                Switch(
                    checked = s.autoEnabled,
                    onCheckedChange = { checked ->
                        if (checked &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.setAutoUpdate(checked)
                    },
                )
            }
            OutlinedButton(
                onClick = { viewModel.checkNow() },
                enabled = !s.checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (s.checking) R.string.update_checking else R.string.update_check_now))
            }
            if (s.checking) CircularProgressIndicator()
            if (s.upToDate) Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.bodySmall)
            if (s.error) Text(stringResource(R.string.update_error), style = MaterialTheme.typography.bodySmall)
            val lastChecked = if (s.lastCheckMs > 0L)
                stringResource(R.string.update_last_check,
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(s.lastCheckMs)))
            else stringResource(R.string.update_never_checked)
            Text(lastChecked, style = MaterialTheme.typography.bodySmall)
        }
    }
}
