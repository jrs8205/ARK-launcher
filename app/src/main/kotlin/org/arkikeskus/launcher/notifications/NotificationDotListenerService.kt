package org.arkikeskus.launcher.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.StatusNotification
import javax.inject.Inject

/**
 * Streams notification-dot counts into [NotificationBadgeRepository]. On every connect/post/remove we
 * recompute the full snapshot from [getActiveNotifications] (simple and always consistent) and group
 * the badge-worthy ones by package + profile.
 *
 * The "badge-worthy" filter mirrors AOSP Launcher3's `NotificationListener.notificationIsValidForUI`:
 * the channel must allow badges, group summaries and content-less notifications are skipped, and
 * ongoing notifications on the legacy default channel don't count.
 *
 * Requires the user to grant notification access (Settings → Notifications → Device & app
 * notifications); until then the system never binds this service.
 */
@AndroidEntryPoint
class NotificationDotListenerService : NotificationListenerService() {

    @Inject
    lateinit var badgeRepository: NotificationBadgeRepository

    private val userManager by lazy { getSystemService(UserManager::class.java) }

    private companion object {
        const val TAG = "NotifDots"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "listener connected")
        refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        badgeRepository.setBadges(emptyMap())
        badgeRepository.setIcons(emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        val tmp = Ranking()
        val counts = HashMap<String, Int>()
        val icons = LinkedHashMap<String, StatusNotification>()
        for (sbn in active) {
            if (sbn == null || !isBadgeWorthy(sbn, ranking, tmp)) continue
            val serial = runCatching { userManager?.getSerialNumberForUser(sbn.user) }.getOrNull() ?: 0L
            val key = "${sbn.packageName}/$serial"
            counts[key] = (counts[key] ?: 0) + 1
            // Keep one small icon per app — the most recent — for the status bar's left side.
            val smallIcon = sbn.notification?.smallIcon
            if (smallIcon != null) {
                val existing = icons[key]
                if (existing == null || sbn.postTime > existing.postTime) {
                    icons[key] = StatusNotification(sbn.key, sbn.packageName, smallIcon, sbn.postTime)
                }
            }
        }
        Log.d(TAG, "badge snapshot: ${counts.size} app(s) badged")
        badgeRepository.setBadges(counts)
        badgeRepository.setIcons(icons.values.sortedByDescending { it.postTime })
    }

    private fun isBadgeWorthy(sbn: StatusBarNotification, ranking: RankingMap?, tmp: Ranking): Boolean {
        val n = sbn.notification ?: return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if (ranking?.getRanking(sbn.key, tmp) == true) {
            if (!tmp.canShowBadge()) return false
            if (tmp.channel?.id == NotificationChannel.DEFAULT_CHANNEL_ID &&
                (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
            ) {
                return false
            }
        }
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)
        return !title.isNullOrEmpty() || !text.isNullOrEmpty()
    }
}
