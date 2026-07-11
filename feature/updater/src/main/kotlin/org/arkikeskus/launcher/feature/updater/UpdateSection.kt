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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.ExpressiveCard
import org.arkikeskus.launcher.ui.expressive.ExpressiveSectionTitle
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

@Composable
fun UpdateSection(modifier: Modifier = Modifier, viewModel: UpdateViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val p = LocalExpressivePalette.current
    // Request POST_NOTIFICATIONS (API 33+) when the user enables auto-update. If denied the
    // feature still works (in-app card); only the background push is skipped.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result intentionally ignored — push is optional */ }
    // Ask only when auto-update notifications are actually in use: on first composition when the
    // default-on setting is active (Android 13+ would otherwise never prompt), and again when the
    // user turns the toggle on. Merely opening the settings page with auto-update off must not ask.
    LaunchedEffect(s.isReleaseBuild, s.autoEnabled) {
        if (s.isReleaseBuild &&
            s.autoEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        ExpressiveSectionTitle(stringResource(R.string.update_section))
        Text(
            stringResource(R.string.update_current_version, s.currentVersion),
            color = p.dim,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )

        if (s.isReleaseBuild) {
            s.available?.let { info ->
                Surface(
                    color = p.surfaceHi,
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = p.shadow,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.update_available_title, info.versionName),
                            color = p.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.update_whats_new),
                            color = Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(info.notes, color = p.dim, fontSize = 13.sp)
                        Button(
                            onClick = { viewModel.installUpdate(info) },
                            enabled = !s.downloading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        ) {
                            Text(stringResource(if (s.downloading) R.string.update_downloading else R.string.update_install))
                        }
                        if (s.downloading) {
                            LinearProgressIndicator(
                                progress = { s.downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Accent,
                                trackColor = p.trackOff,
                            )
                            Text(
                                stringResource(R.string.update_downloading_percent, s.downloadProgress),
                                color = p.dim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            ExpressiveCard {
                Text(
                    stringResource(R.string.update_auto_toggle),
                    color = p.text,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = s.autoEnabled,
                    // The permission request lives ONLY in the autoEnabled-keyed LaunchedEffect
                    // above: launching here too would fire the SAME launcher twice on enable
                    // (once from this callback, again when the state flip re-runs the effect).
                    onCheckedChange = { viewModel.setAutoUpdate(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Accent,
                        checkedThumbColor = Color.White,
                        checkedBorderColor = Accent,
                        uncheckedTrackColor = p.trackOff,
                        uncheckedThumbColor = p.thumbOff,
                        uncheckedBorderColor = p.trackOff,
                    ),
                )
            }
            OutlinedButton(
                onClick = { viewModel.checkNow() },
                enabled = !s.checking,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            ) {
                Text(stringResource(if (s.checking) R.string.update_checking else R.string.update_check_now))
            }
            if (s.checking) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = Accent)
                }
            }
            if (s.upToDate) {
                Text(
                    stringResource(R.string.update_up_to_date),
                    color = p.dim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            s.error?.let { err ->
                Text(
                    stringResource(
                        when (err) {
                            UpdateError.CHECK -> R.string.update_check_failed
                            UpdateError.INSTALL -> R.string.update_install_failed
                        },
                    ),
                    color = p.dim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            val lastChecked = if (s.lastCheckMs > 0L)
                stringResource(
                    R.string.update_last_check,
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(s.lastCheckMs)),
                )
            else stringResource(R.string.update_never_checked)
            Text(
                lastChecked,
                color = p.faint,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
    }
}
