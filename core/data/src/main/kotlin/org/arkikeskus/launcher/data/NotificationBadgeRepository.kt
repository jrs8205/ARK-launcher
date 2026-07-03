package org.arkikeskus.launcher.data

import android.graphics.drawable.Icon
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One active notification's status-bar glyph: its small icon plus the app + post time it came from. */
data class StatusNotification(
    val key: String,
    val packageName: String,
    val icon: Icon,
    val postTime: Long,
)

/**
 * Holds the current notification-dot counts, keyed by [org.arkikeskus.launcher.model.AppItem.badgeKey]
 * ("packageName/userSerial"). The platform [android.service.notification.NotificationListenerService]
 * (in the app module) pushes snapshots here; ViewModels observe [badges] and the UI draws a dot.
 *
 * A plain singleton (not Room/DataStore): notification state is ephemeral and rebuilt on every
 * listener reconnect, so there is nothing to persist.
 */
@Singleton
class NotificationBadgeRepository @Inject constructor() {
    private val _badges = MutableStateFlow<Map<String, Int>>(emptyMap())
    val badges: StateFlow<Map<String, Int>> = _badges.asStateFlow()

    /** Active notifications' small icons (most-recent first), for the home status bar's left side. */
    private val _icons = MutableStateFlow<List<StatusNotification>>(emptyList())
    val icons: StateFlow<List<StatusNotification>> = _icons.asStateFlow()

    /** [SystemClock.elapsedRealtime] of the last heads-up-worthy notification post (0 = none yet). The
     *  home status bar blanks the themed bar for a window after this while the system TRANSIENTLY reveals
     *  its own bar over the content — a reveal WindowManager deliberately does NOT surface as an inset, so
     *  it can't be seen via WindowInsets and this app-side signal is the only option (see StatusBar).
     *  A TIMESTAMP (not a counter) is deliberate: when the flow is re-subscribed (e.g. returning home) the
     *  replayed latest value is simply already-expired, so an old heads-up never re-blanks the bar. */
    private val _headsUp = MutableStateFlow(0L)
    val headsUp: StateFlow<Long> = _headsUp.asStateFlow()

    /** Called by the listener when a heads-up-worthy notification is posted. */
    fun notifyHeadsUp() {
        _headsUp.value = SystemClock.elapsedRealtime()
    }

    /** Replaces the full set of badge counts with a fresh snapshot from the listener. */
    fun setBadges(counts: Map<String, Int>) {
        _badges.value = counts
    }

    /** Replaces the active-notification icon list with a fresh snapshot from the listener. */
    fun setIcons(icons: List<StatusNotification>) {
        _icons.value = icons
    }
}
