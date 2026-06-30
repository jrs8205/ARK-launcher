package org.arkikeskus.launcher.data

import android.graphics.drawable.Icon
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

    /** Replaces the full set of badge counts with a fresh snapshot from the listener. */
    fun setBadges(counts: Map<String, Int>) {
        _badges.value = counts
    }

    /** Replaces the active-notification icon list with a fresh snapshot from the listener. */
    fun setIcons(icons: List<StatusNotification>) {
        _icons.value = icons
    }
}
