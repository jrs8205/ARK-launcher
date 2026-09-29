package org.arkikeskus.launcher.feature.home

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * Fires a notification's content intent the way a tap in the shade would. Android 14+ no longer
 * grants the sender's foreground privileges implicitly; the launcher is in the foreground on tap,
 * so the background-activity-start grant is ours to give. Returns false when the send failed
 * (a cancelled PendingIntent, typically).
 */
internal fun sendNotificationIntent(context: Context, intent: PendingIntent): Boolean = runCatching {
    val options = ActivityOptions.makeBasic()
    if (Build.VERSION.SDK_INT >= 34) {
        options.setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
        )
    }
    intent.send(context, 0, null, null, null, null, options.toBundle())
}.isSuccess
