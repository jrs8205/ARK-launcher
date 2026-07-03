package org.arkikeskus.launcher.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
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
 * the notifications by package + profile.
 *
 * Two filters, deliberately different (this is why the status-bar icons show more than the dots):
 * - **Dots** use the strict "badge-worthy" filter, mirroring AOSP Launcher3's
 *   `NotificationListener.notificationIsValidForUI`: the channel must allow badges, group summaries and
 *   content-less notifications are skipped, and ongoing notifications on the legacy default channel
 *   don't count. This keeps the home-icon dots meaningful.
 * - **Status-bar icons** use a looser "icon-worthy" filter (not a group summary + has a title or text),
 *   like the real system status bar — so low-priority/silent notifications whose channel sets
 *   `canShowBadge = false` (e.g. Google News) still show their glyph in the bar.
 *
 * Requires the user to grant notification access (Settings → Notifications → Device & app
 * notifications); until then the system never binds this service.
 */
@AndroidEntryPoint
class NotificationDotListenerService : NotificationListenerService() {

    @Inject
    lateinit var badgeRepository: NotificationBadgeRepository

    private val userManager by lazy { getSystemService(UserManager::class.java) }

    /** Keys that have already fired a heads-up, so a FLAG_ONLY_ALERT_ONCE re-post doesn't re-trigger.
     *  Touched only from NLS callbacks (main thread), so it needs no synchronisation. */
    private val alertedKeys = HashSet<String>()

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
        alertedKeys.clear()
        // Aggressive OEM battery managers (Samsung, Xiaomi, …) can unbind the listener; ask the system
        // to rebind so dots + status-bar icons come back on their own instead of the user having to
        // re-toggle notification access. No-op if the system declines.
        runCatching { requestRebind(ComponentName(this, NotificationDotListenerService::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // A heads-up post makes the system transiently show ITS status bar over our content; tell the
        // repo so the home screen can blank the themed bar for that window (the reveal isn't dispatched
        // as an inset, so this is the only app-observable signal — see the audit note in StatusBar).
        if (sbn != null && isHeadsUpWorthy(sbn) && shouldAlert(sbn)) badgeRepository.notifyHeadsUp()
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) alertedKeys.remove(sbn.key)
        refresh()
    }

    /** A FLAG_ONLY_ALERT_ONCE notification heads-ups only on its FIRST post; a re-post (same key) must
     *  not re-trigger (mirrors SystemUI's alertAgain / shouldHunAgain) — otherwise a frequently-updating
     *  ongoing HIGH-importance notification would keep the themed bar blanked forever. */
    private fun shouldAlert(sbn: StatusBarNotification): Boolean {
        val firstTime = alertedKeys.add(sbn.key)
        val onlyOnce = ((sbn.notification?.flags ?: 0) and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        return firstTime || !onlyOnce
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        val tmp = Ranking()
        val counts = HashMap<String, Int>()
        val icons = LinkedHashMap<String, StatusNotification>()
        for (sbn in active) {
            if (sbn == null) continue
            val serial = runCatching { userManager?.getSerialNumberForUser(sbn.user) }.getOrNull() ?: 0L
            val key = "${sbn.packageName}/$serial"
            // Dots: strict badge-worthy filter (meaningful home-icon badges).
            if (isBadgeWorthy(sbn, ranking, tmp)) {
                counts[key] = (counts[key] ?: 0) + 1
            }
            // Status-bar icons: looser filter so silent/low-priority notifs (Google News etc.) show too.
            // Keep one small icon per app — the most recent.
            if (isIconWorthy(sbn)) {
                val smallIcon = sbn.notification?.smallIcon
                if (smallIcon != null) {
                    val existing = icons[key]
                    if (existing == null || sbn.postTime > existing.postTime) {
                        icons[key] = StatusNotification(sbn.key, sbn.packageName, smallIcon, sbn.postTime)
                    }
                }
            }
        }
        Log.d(TAG, "badge snapshot: ${counts.size} app(s) badged")
        badgeRepository.setBadges(counts)
        badgeRepository.setIcons(icons.values.sortedByDescending { it.postTime })
    }

    /**
     * Approximates SystemUI's heads-up decision (NotificationInterruptStateProvider.shouldHeadsUp): a
     * high-importance (or full-screen-intent) notification that isn't peek-suppressed. On a match the
     * system transiently reveals its own status bar over our content. A false positive only briefly
     * blanks the themed bar, so the check is deliberately lenient.
     */
    private fun isHeadsUpWorthy(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val r = Ranking()
        val ranked = runCatching { currentRanking?.getRanking(sbn.key, r) == true }.getOrDefault(false)
        val importance = if (ranked) r.importance else NotificationManager.IMPORTANCE_DEFAULT
        val peekSuppressed = ranked &&
            (r.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK) != 0
        return !peekSuppressed &&
            (importance >= NotificationManager.IMPORTANCE_HIGH || n.fullScreenIntent != null)
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

    /**
     * Looser filter for the status-bar icons: like the system status bar, it shows a glyph for any real
     * notification — including silent / low-importance ones whose channel disables badges (Google News,
     * "now playing", etc.). We only drop group summaries (they duplicate their children's icons) and
     * content-less placeholders. Notably it does NOT consult `canShowBadge()`, which is what the strict
     * [isBadgeWorthy] dot filter uses.
     */
    private fun isIconWorthy(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)
        return !title.isNullOrEmpty() || !text.isNullOrEmpty()
    }
}
