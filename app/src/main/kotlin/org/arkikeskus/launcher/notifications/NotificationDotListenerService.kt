package org.arkikeskus.launcher.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.BatchSchedule
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.PeopleGrouping
import org.arkikeskus.launcher.data.PersonEntry
import org.arkikeskus.launcher.data.PersonEventKind
import org.arkikeskus.launcher.data.ReplyAction
import org.arkikeskus.launcher.data.SettingsRepository
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
 * - **People tiles** take only conversation-like notifications (messages, calls, mail — see
 *   [personEntry]) and reduce each to its sender, newest text and quick actions; the repository
 *   groups them by person across apps.
 * - **Batch delivery** (opt-in): a fresh message from someone who isn't pinned is snoozed until the
 *   next delivery time, so the shade and the home stay quiet in between; the snoozed ones still feed
 *   the people tiles as "waiting". Pinned people and missed calls are never held.
 *
 * Requires the user to grant notification access (Settings → Notifications → Device & app
 * notifications); until then the system never binds this service.
 */
@AndroidEntryPoint
class NotificationDotListenerService : NotificationListenerService() {

    @Inject
    lateinit var badgeRepository: NotificationBadgeRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** Lives while the listener is connected; carries the batch settings + pins into [holdForBatch]. */
    private var settingsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var batchEnabled = false
    @Volatile private var batchTimes: List<Int> = emptyList()
    /** Person keys that always come through: pinned people and the aliases that merge into them. */
    @Volatile private var vipKeys: Set<String> = emptySet()

    /** Packages that handle mailto: links — the mail clients. Their notifications carry the sender
     *  as the title and the subject as the text, but almost none set CATEGORY_EMAIL (Gmail doesn't),
     *  so the app being a mail client is the signal. Re-read on every connect. */
    @Volatile private var mailPackages: Set<String> = emptySet()

    /** Delivery time per key THIS launcher snoozed for the batch: labels the tile with the right
     *  time, releases (doesn't re-hold) the system's repost, and tells our snoozes apart from ones
     *  the user made in the shade. Persisted so a process restart keeps the distinction; the
     *  postTime age check in [holdForBatch] still covers a lost entry. */
    private val heldUntil = HashMap<String, Long>()
    private var heldLoaded = false

    private fun persistHeld() {
        val snapshot = HashMap(heldUntil)
        scope.launch { runCatching { settingsRepository.setHeldNotifications(snapshot) } }
    }

    private val userManager by lazy { getSystemService(UserManager::class.java) }

    /** Keys that have already fired a heads-up, so a FLAG_ONLY_ALERT_ONCE re-post doesn't re-trigger.
     *  Touched only from NLS callbacks (main thread), so it needs no synchronisation. */
    private val alertedKeys = HashSet<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var snapshotRetriesLeft = 0
    private val retryRefresh = Runnable { refresh() }

    private companion object {
        const val TAG = "NotifDots"
        const val MAX_SNAPSHOT_RETRIES = 2
        const val SNAPSHOT_RETRY_DELAY_MS = 500L

        /** A post older than this is a batch repost or an update, never a new message to hold. */
        const val HOLD_FRESH_MS = 90_000L

        /** Don't bother snoozing when the delivery is (nearly) now. */
        const val MIN_HOLD_MS = 60_000L

        /** A repost slightly before the recorded delivery time still counts as delivered. */
        const val RELEASE_SLACK_MS = 60_000L

        /** The generic "missed call" label in the languages a dialer here is likely to use. */
        val CALL_WORDS = listOf("call", "puhelu", "soitto", "samtal", "anruf", "appel", "llamada")
    }

    /** Refresh now, allowing a bounded number of delayed retries if the snapshot read fails —
     *  without one, a single transient Binder failure would leave a removed notification visible
     *  until the next callback happens to fire. */
    private fun refreshWithRetries() {
        snapshotRetriesLeft = MAX_SNAPSHOT_RETRIES
        handler.removeCallbacks(retryRefresh)
        refresh()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "listener connected")
        badgeRepository.registerCanceller { key -> runCatching { cancelNotification(key) } }
        mailPackages = queryMailPackages()
        settingsJob?.cancel()
        settingsJob = combine(
            settingsRepository.settings, settingsRepository.pinnedPeople, settingsRepository.peopleAliases,
        ) { s, pinned, aliases ->
            val pins = pinned.map { it.key }.toSet()
            Triple(s.peopleBatchEnabled, BatchSchedule.parse(s.peopleBatchTimes), pins + aliases.filterValues { it in pins }.keys)
        }.onEach { (enabled, times, vips) ->
            batchEnabled = enabled
            batchTimes = times
            vipKeys = vips
        }.launchIn(scope)
        if (!heldLoaded) {
            scope.launch {
                val saved = runCatching { settingsRepository.heldNotifications.first() }.getOrDefault(emptyMap())
                for ((k, v) in saved) heldUntil.putIfAbsent(k, v)
                heldLoaded = true
                refreshWithRetries()
            }
        }
        refreshWithRetries()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        handler.removeCallbacks(retryRefresh)
        settingsJob?.cancel()
        settingsJob = null
        badgeRepository.clearCanceller()
        badgeRepository.setBadges(emptyMap())
        badgeRepository.setIcons(emptyList())
        badgeRepository.setPeople(emptyList())
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
        if (sbn != null && holdForBatch(sbn)) {
            // Snoozed: it leaves the shade now and comes back at the delivery time as a fresh post.
            refreshWithRetries()
            return
        }
        if (sbn != null && isHeadsUpWorthy(sbn) && shouldAlert(sbn)) badgeRepository.notifyHeadsUp()
        refreshWithRetries()
    }

    /**
     * Snoozes a just-posted message from a non-pinned person until the next batch time. Returns
     * true when it did. Only a FRESH post is held: the system reposts a snoozed notification with
     * its original postTime once the snooze ends, so an old postTime means "delivered by the
     * batch" (or an app update to a held one) and it is left alone.
     */
    private fun holdForBatch(sbn: StatusBarNotification): Boolean {
        if (!batchEnabled) return false
        val now = System.currentTimeMillis()
        val until = heldUntil[sbn.key]
        if (until != null && now >= until - RELEASE_SLACK_MS) {
            heldUntil.remove(sbn.key)
            persistHeld()
            return false
        }
        if (now - sbn.postTime > HOLD_FRESH_MS) return false
        val entry = runCatching { personEntry(sbn, 0L, currentRanking, Ranking()) }.getOrNull() ?: return false
        if (entry.kind == PersonEventKind.MISSED_CALL) return false
        if (PeopleGrouping.personKey(entry.name) in vipKeys) return false
        val deliverAt = BatchSchedule.nextDelivery(now, batchTimes) ?: return false
        val delay = deliverAt - now
        if (delay < MIN_HOLD_MS) return false
        val snoozed = runCatching { snoozeNotification(sbn.key, delay) }.isSuccess
        if (snoozed) {
            heldUntil[sbn.key] = deliverAt
            persistHeld()
        }
        return snoozed
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) alertedKeys.remove(sbn.key)
        // A snooze also arrives here (REASON_SNOOZED); keep its delivery time. Only a real removal
        // (dismissed, cancelled by the app) forgets it.
        if (sbn != null && sbn.key in heldUntil) {
            val stillSnoozed = runCatching { snoozedNotifications?.any { it.key == sbn.key } }.getOrNull() == true
            if (!stillSnoozed) {
                heldUntil.remove(sbn.key)
                persistHeld()
            }
        }
        refreshWithRetries()
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
        val active = runCatching { activeNotifications }.getOrNull()
        if (active == null) {
            // A transient Binder failure: keep the last good snapshot (a stale removal self-heals on
            // the next post/remove callback) but leave a trail so a persistent failure is visible.
            Log.w(TAG, "activeNotifications unavailable; keeping the previous snapshot")
            if (snapshotRetriesLeft > 0) {
                snapshotRetriesLeft--
                handler.postDelayed(retryRefresh, SNAPSHOT_RETRY_DELAY_MS)
            }
            return
        }
        val ranking = runCatching { currentRanking }.getOrNull()
        val tmp = Ranking()
        val counts = HashMap<String, Int>()
        val iconCounts = HashMap<String, Int>()
        val visual = LinkedHashMap<String, StatusNotification>()
        val openable = HashMap<String, StatusNotification>()
        val people = ArrayList<PersonEntry>()
        for (sbn in active) {
            if (sbn == null) continue
            val serial = runCatching { userManager?.getSerialNumberForUser(sbn.user) }.getOrNull() ?: 0L
            val key = "${sbn.packageName}/$serial"
            // People tiles: conversation-like notifications reduced to sender + text + actions; any
            // other real (icon-worthy, not ongoing) notification becomes an app-grouped entry.
            val person = runCatching { personEntry(sbn, serial, ranking, tmp) }.getOrNull()
            when {
                person != null -> people.add(person)
                isIconWorthy(sbn) && (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) == 0 ->
                    runCatching { appEntry(sbn, serial) }.getOrNull()?.let(people::add)
            }
            // Dots: strict badge-worthy filter (meaningful home-icon badges).
            if (isBadgeWorthy(sbn, ranking, tmp)) {
                counts[key] = (counts[key] ?: 0) + 1
            }
            // Status-bar icons + the notifications widget: looser filter so silent/low-priority
            // notifs (Google News etc.) show too. One entry per app — the most recent — carrying
            // the app's icon-worthy total so the widget's count always matches what it lists.
            if (isIconWorthy(sbn)) {
                val smallIcon = sbn.notification?.smallIcon ?: continue
                iconCounts[key] = (iconCounts[key] ?: 0) + 1
                val flags = sbn.notification?.flags ?: 0
                val entry = StatusNotification(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    icon = smallIcon,
                    postTime = sbn.postTime,
                    userSerial = serial,
                    contentIntent = sbn.notification?.contentIntent,
                    autoCancel = (flags and Notification.FLAG_AUTO_CANCEL) != 0,
                    preferSmallIcon = sbn.notification?.extras
                        ?.getBoolean(Notification.EXTRA_PREFER_SMALL_ICON) == true,
                )
                val curVisual = visual[key]
                if (curVisual == null || sbn.postTime > curVisual.postTime) visual[key] = entry
                if (entry.contentIntent != null) {
                    val curOpen = openable[key]
                    if (curOpen == null || sbn.postTime > curOpen.postTime) openable[key] = entry
                }
            }
        }
        Log.d(TAG, "badge snapshot: ${counts.size} app(s) badged")
        badgeRepository.setBadges(counts)
        badgeRepository.setIcons(
            visual.map { (k, v) ->
                // Visual icon = newest notification; tap action = newest OPENABLE notification, so a
                // newer intentless post never buries an older tappable one. count = app's total.
                val open = openable[k]
                v.copy(
                    count = iconCounts[k] ?: 1,
                    key = open?.key ?: v.key,
                    contentIntent = open?.contentIntent,
                    autoCancel = open?.autoCancel ?: false,
                )
            }.sortedByDescending { it.postTime },
        )
        // Notifications THIS launcher snoozed for the batch are out of the shade but still
        // someone's message: they feed the tiles as "waiting", never the dots or the status bar.
        // Read even when the batch has since been switched off: a listener can neither cancel nor
        // un-snooze a snoozed notification (the public API has no call for it), so the system
        // delivers it at its time regardless, and hiding it in between would make it vanish for
        // the user. A notification the user snoozed in the shade is not ours and stays out.
        if (heldUntil.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val snoozed = runCatching { snoozedNotifications }.getOrNull().orEmpty().filterNotNull()
            val snoozedKeys = snoozed.map { it.key }.toSet()
            for (sbn in snoozed) {
                val until = heldUntil[sbn.key] ?: continue
                val serial = runCatching { userManager?.getSerialNumberForUser(sbn.user) }.getOrNull() ?: 0L
                val entry = runCatching { personEntry(sbn, serial, ranking, tmp) }.getOrNull() ?: continue
                people.add(entry.copy(heldUntil = until))
            }
            // Forget entries whose notification is gone for good (delivered and then dismissed, or
            // cancelled by its app while snoozed), so the persisted map doesn't grow forever.
            val stale = heldUntil.filter { (k, until) -> k !in snoozedKeys && now > until + RELEASE_SLACK_MS }
            if (stale.isNotEmpty()) {
                stale.keys.forEach { heldUntil.remove(it) }
                persistHeld()
            }
        }
        badgeRepository.setPeople(PeopleGrouping.group(people))
    }

    /**
     * Reduces a notification to a [PersonEntry] when it is about a person, else null. Recognized, in
     * order: a MessagingStyle (any chat app; newest message + its sender), a call / missed call, a
     * system-flagged conversation or CATEGORY_MESSAGE (sender = title), and mail — CATEGORY_EMAIL
     * or any notification from a mail client (sender = title, subject = text). Group summaries and
     * ongoing notifications (an active call, "now playing") are skipped.
     */
    private fun personEntry(
        sbn: StatusBarNotification, serial: Long, ranking: RankingMap?, tmp: Ranking,
    ): PersonEntry? {
        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        var subject: String? = null
        val ranked = ranking?.getRanking(sbn.key, tmp) == true
        // Ranking's conversation APIs are public from Android 12. Android 11 still uses the
        // MessagingStyle, category and title fallbacks below to identify people and batch messages.
        val conversation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ranked && tmp.isConversation
        val shortcutLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ranked) {
            tmp.conversationShortcutInfo?.shortLabel?.toString()
        } else null

        var name: String
        var preview: String? = text
        var count = 1
        var icon: android.graphics.drawable.Icon? = null
        var uri: String? = null
        var sender: String? = null
        // A chat app's large icon is the contact's or group's picture; a dialer's, the caller's.
        val largeIcon = runCatching { n.getLargeIcon() }.getOrNull()
        val kind: PersonEventKind
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        when {
            style != null -> {
                val messages = style.messages.filter { !it.text.isNullOrBlank() }
                val last = messages.lastOrNull()
                val groupTitle = style.conversationTitle?.toString()?.trim()
                    ?.takeIf { style.isGroupConversation && it.isNotEmpty() }
                val lastSender = last?.person?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                name = groupTitle ?: lastSender.orEmpty().ifEmpty { shortcutLabel ?: title }
                preview = last?.text?.toString()?.trim() ?: text
                count = messages.size.coerceAtLeast(1)
                // The tile is the group when there is one, so its picture beats the sender's.
                val personIcon = last?.person?.icon?.toIcon(this)
                icon = if (groupTitle != null) largeIcon ?: personIcon else personIcon ?: largeIcon
                if (groupTitle != null) sender = lastSender
                uri = last?.person?.uri
                kind = PersonEventKind.MESSAGE
            }
            n.category == Notification.CATEGORY_MISSED_CALL || n.category == Notification.CATEGORY_CALL -> {
                // Dialers disagree on which field carries the caller: Google's "Missed call" / "Mikko"
                // vs. Samsung's "Mikko" / "Missed call". The people list settles it when present;
                // otherwise the field that reads like the generic label is the label.
                val person = runCatching {
                    @Suppress("DEPRECATION")
                    extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)?.firstOrNull()
                }.getOrNull()
                val swap = looksLikeCallLabel(title) && !text.isNullOrEmpty() && !looksLikeCallLabel(text)
                name = person?.name?.toString()?.trim().orEmpty().ifEmpty { if (swap) text.orEmpty() else title }
                preview = if (swap) title else text
                icon = person?.icon ?: largeIcon
                uri = person?.uri
                kind = PersonEventKind.MISSED_CALL
            }
            conversation || n.category == Notification.CATEGORY_MESSAGE -> {
                name = shortcutLabel ?: title
                icon = largeIcon
                kind = PersonEventKind.MESSAGE
            }
            n.category == Notification.CATEGORY_EMAIL || sbn.packageName in mailPackages -> {
                // A mail client's other notices ("Syncing…", "Sending…") carry no sender/subject pair
                // or are ongoing; a real mail has both.
                if (title.isEmpty() || text == null) return null
                name = title
                // Mail clients put the subject in the text and the body's first lines in the
                // expanded (big) text; show the subject as a title line and the body under it.
                subject = text
                preview = bigText?.removePrefix(text)?.trim()?.takeIf { it.isNotEmpty() } ?: text
                kind = PersonEventKind.EMAIL
            }
            else -> return null
        }
        if (name.isBlank()) return null

        val reply = n.actions?.firstNotNullOfOrNull { action ->
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return@firstNotNullOfOrNull null
            val semantic = action.semanticAction
            if (semantic != Notification.Action.SEMANTIC_ACTION_REPLY &&
                semantic != Notification.Action.SEMANTIC_ACTION_NONE
            ) return@firstNotNullOfOrNull null
            action.actionIntent?.let { ReplyAction(it, input) }
        }
        val callBack = n.actions
            ?.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_CALL }
            ?.actionIntent
        return PersonEntry(
            key = sbn.key,
            name = name,
            title = subject,
            text = preview,
            postTime = sbn.postTime,
            packageName = sbn.packageName,
            userSerial = serial,
            kind = kind,
            count = count,
            contentIntent = n.contentIntent,
            autoCancel = (n.flags and Notification.FLAG_AUTO_CANCEL) != 0,
            reply = reply,
            callBack = callBack,
            personIcon = icon,
            sender = sender,
            personUri = uri,
        )
    }

    /** Any other notification as an entry under its app: label as the name, title + text shown. */
    private fun appEntry(sbn: StatusBarNotification, serial: Long): PersonEntry? {
        val n = sbn.notification ?: return null
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val body = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        // Some apps repeat the title at the start of the text; the tile already shows the title.
        val text = if (title != null && body != null) body.removePrefix(title).trim().ifEmpty { body } else body
        if (title == null && text == null) return null
        val label = appLabels.getOrPut(sbn.packageName) {
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            }.getOrDefault(sbn.packageName)
        }
        val reply = n.actions?.firstNotNullOfOrNull { action ->
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return@firstNotNullOfOrNull null
            action.actionIntent?.let { ReplyAction(it, input) }
        }
        return PersonEntry(
            key = sbn.key,
            name = label,
            groupKey = "app:${sbn.packageName}/$serial",
            title = title,
            text = text,
            postTime = sbn.postTime,
            packageName = sbn.packageName,
            userSerial = serial,
            kind = PersonEventKind.APP,
            contentIntent = n.contentIntent,
            autoCancel = (n.flags and Notification.FLAG_AUTO_CANCEL) != 0,
            reply = reply,
        )
    }

    /** App labels by package, filled lazily; a label rarely changes while the listener lives. */
    private val appLabels = HashMap<String, String>()

    /** The installed apps that offer to compose mail (handle mailto:), i.e. the mail clients. */
    private fun queryMailPackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:someone@example.com"))
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /** True for the generic "Missed call"-style label a dialer puts in whichever field isn't the name. */
    private fun looksLikeCallLabel(s: String): Boolean {
        val l = s.lowercase()
        return CALL_WORDS.any { it in l }
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
     * "now playing", etc.) and custom-view/MediaStyle ones that carry a small icon but no title/text.
     * We only drop group summaries (they duplicate their children's icons). Notably it does NOT consult
     * `canShowBadge()`, which is what the strict [isBadgeWorthy] dot filter uses.
     */
    private fun isIconWorthy(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        // A custom-RemoteViews / MediaStyle notification can carry a user-visible glyph with neither
        // EXTRA_TITLE nor EXTRA_TEXT — the system bar shows it, so we do too. Require a small icon.
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)
        return !title.isNullOrEmpty() || !text.isNullOrEmpty() || n.smallIcon != null
    }
}
