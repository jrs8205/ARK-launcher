package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.arkikeskus.launcher.designsystem.theme.LauncherTextStyles
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home screen (M1/M2): the two-row status bar pinned to the top, a big clock + date below, and a
 * swipe-up gesture to open the app drawer. Other styles, dock and home grid arrive later.
 */
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var triggered = false
                detectVerticalDragGestures(
                    onDragStart = { triggered = false },
                    onDragEnd = { triggered = false },
                    onVerticalDrag = { _, dragAmount ->
                        if (!triggered && dragAmount < -30f) {
                            triggered = true
                            onOpenDrawer()
                        }
                    },
                )
            },
    ) {
        StatusBlock(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(start = 24.dp, top = 80.dp),
        ) {
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
            text = stringResource(R.string.home_open_drawer_hint),
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}
