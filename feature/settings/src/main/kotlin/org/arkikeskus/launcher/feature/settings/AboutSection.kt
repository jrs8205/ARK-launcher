package org.arkikeskus.launcher.feature.settings

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.ui.DefaultLauncher
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** The row that makes ARK-launcher the home app; it says whether it already is, and looks again whenever the
 *  page comes back to the front and when the system's chooser returns. */
@Composable
internal fun DefaultLauncherRow() {
    val context = LocalContext.current
    var isDefaultLauncher by remember { mutableStateOf(DefaultLauncher.isDefault(context)) }
    // The home app can also change in the system settings while this screen is behind them.
    LifecycleResumeEffect(Unit) {
        isDefaultLauncher = DefaultLauncher.isDefault(context)
        onPauseOrDispose { }
    }
    // Re-check after the user returns from the system "default home app" / role chooser.
    val setDefaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { isDefaultLauncher = DefaultLauncher.isDefault(context) }
    ExpressiveActionRow(
        label = stringResource(R.string.settings_set_default),
        description = stringResource(
            if (isDefaultLauncher) R.string.settings_default_current else R.string.settings_default_tap,
        ),
    ) {
        runCatching { setDefaultLauncher.launch(DefaultLauncher.requestIntent(context)) }
    }
}

/** Settings > Updates: the releases page in the browser. */
@Composable
internal fun UpdatesRow() {
    val context = LocalContext.current
    SettingRow("U1") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_updates_check),
            description = stringResource(R.string.settings_updates_check_desc),
        ) { openLink(context, RELEASES_URL) }
    }
}

/** The installed build, last of all, so a glance answers "which version is this" without the system's app info. */
@Composable
internal fun VersionLine() {
    val context = LocalContext.current
    val palette = LocalExpressivePalette.current
    val installedVersion = remember(context) { installedVersionLabel(context) }
    if (installedVersion != null) {
        Text(
            text = installedVersion,
            color = palette.dim,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 4.dp),
        )
    }
}

private const val RELEASES_URL = "https://github.com/jrs8205/ARK-launcher/releases"
