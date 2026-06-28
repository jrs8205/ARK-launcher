package org.arkikeskus.launcher.feature.updater

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun UpdateSection(modifier: Modifier = Modifier, viewModel: UpdateViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
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
                Switch(checked = s.autoEnabled, onCheckedChange = { viewModel.setAutoUpdate(it) })
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
        }
    }
}
