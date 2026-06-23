package org.arkikeskus.launcher.feature.home

import android.annotation.SuppressLint
import android.content.Context

/**
 * Opens the system notification shade via the hidden StatusBarManager API. Requires the normal
 * EXPAND_STATUS_BAR permission. No-ops on OEMs that don't expose the method.
 */
object NotificationShade {
    @SuppressLint("WrongConstant")
    fun expand(context: Context) {
        try {
            val service = context.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(service)
        } catch (e: Exception) {
            // Ignore — the gesture simply does nothing if unavailable.
        }
    }
}
