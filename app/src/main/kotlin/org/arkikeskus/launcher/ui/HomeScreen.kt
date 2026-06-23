package org.arkikeskus.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.arkikeskus.launcher.R
import org.arkikeskus.launcher.designsystem.theme.LauncherTextStyles
import org.arkikeskus.launcher.role.DefaultLauncher
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Minimal M0 home screen: live clock + date over the wallpaper, plus a button to become the
 * default launcher. The real paged home (grid, dock, status block) lands in M1+.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val finnish = remember { Locale("fi", "FI") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("H.mm", finnish) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE d. MMMM", finnish) }

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000L)
        }
    }

    var isDefault by remember { mutableStateOf(DefaultLauncher.isDefault(context)) }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        isDefault = DefaultLauncher.isDefault(context)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            Text(
                text = now.format(timeFormatter),
                style = LauncherTextStyles.clockSelkea,
                color = Color.White,
            )
            Text(
                text = now.format(dateFormatter).replaceFirstChar { it.uppercase(finnish) },
                color = Color.White.copy(alpha = 0.85f),
            )
        }

        Text(
            text = stringResource(R.string.app_name),
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.align(Alignment.Center),
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { roleLauncher.launch(DefaultLauncher.requestIntent(context)) },
                enabled = !isDefault,
            ) {
                Text(
                    text = if (isDefault) {
                        stringResource(R.string.default_launcher_active)
                    } else {
                        stringResource(R.string.set_default_launcher)
                    },
                )
            }
        }
    }
}
