package org.arkikeskus.launcher.feature.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.text.DateFormat

/**
 * The phone's time-of-day formatter, kept current. `DateFormat.getTimeFormat` hands out a
 * SimpleDateFormat that captures the time zone and the 12/24-hour pattern when it is made, so one
 * kept for long shows the old zone after a landing and the old pattern after a settings flip: it is
 * made again when the system announces a time, zone or locale change or the 12/24-hour setting
 * changes. Shared by the status bar clock and the smartspace clock, alarm and event times.
 */
@Composable
internal fun rememberTimeFormat(): DateFormat {
    val context = LocalContext.current
    var formatEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { formatEpoch++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { formatEpoch++ }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return remember(formatEpoch) { android.text.format.DateFormat.getTimeFormat(context) }
}
