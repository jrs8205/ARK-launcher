package org.arkikeskus.launcher.data

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.drawable.Icon

/** What a tile's notification is about; decides the tile's icon and its quick actions. [APP] is
 *  any other notification (news, deliveries, system), grouped by app instead of by person. */
enum class PersonEventKind { MESSAGE, MISSED_CALL, EMAIL, APP }

/** A notification's inline-reply action: the app's intent plus the RemoteInput it expects. */
data class ReplyAction(
    val intent: PendingIntent,
    val remoteInput: RemoteInput,
)

/**
 * One conversation-like notification reduced to what the people widget needs. Built by the
 * listener (app module) from MessagingStyle / conversation / call / email notifications; anything
 * that isn't about a person (downloads, system, news) never becomes an entry.
 */
data class PersonEntry(
    /** StatusBarNotification.key — what [NotificationBadgeRepository.cancelNotification] takes. */
    val key: String,
    /** The sender or conversation display name as the app posted it (the app label for [APP]). */
    val name: String,
    /** Groups this entry under a fixed key instead of the name (app tiles: "app:package/serial"). */
    val groupKey: String? = null,
    /** The notification title, for app tiles (a person's tile shows the name instead). */
    val title: String? = null,
    /** The newest message text (null for a call, or when the app posted none). */
    val text: String?,
    val postTime: Long,
    val packageName: String,
    val userSerial: Long,
    val kind: PersonEventKind,
    /** Messages this one notification carries (a MessagingStyle bundles several); at least 1. */
    val count: Int = 1,
    val contentIntent: PendingIntent? = null,
    val autoCancel: Boolean = false,
    val reply: ReplyAction? = null,
    /** A dialer's "call back" action on a missed call, when it provided one. */
    val callBack: PendingIntent? = null,
    /** The best avatar the notification carries: the sender's Person icon, or the notification's
     *  large icon (a chat app's contact or group picture). Null when it carries none. */
    val personIcon: Icon? = null,
    /** In a group conversation: who wrote the newest message (the tile itself is the group). */
    val sender: String? = null,
    /** The sender's Person.uri (a `tel:` or contact URI) when the app attached one. */
    val personUri: String? = null,
    /** Epoch ms the launcher batch releases this notification; 0 = shown normally. */
    val heldUntil: Long = 0L,
) {
    // Icon, PendingIntent and RemoteInput compare by identity and are unparcelled afresh on every
    // listener snapshot, so a generated equals would never match and each refresh would repaint the
    // whole widget. key + postTime already identify the notification version the handles belong to.
    private val fingerprint: List<Any?>
        get() = listOf(
            key, name, groupKey, title, text, postTime, packageName, userSerial, kind, count, autoCancel,
            contentIntent != null, reply != null, callBack != null, personIcon != null, sender, personUri, heldUntil,
        )

    override fun equals(other: Any?): Boolean = other is PersonEntry && fingerprint == other.fingerprint
    override fun hashCode(): Int = fingerprint.hashCode()
}

/** Every live notification of one person, across apps; [entries] newest first. */
data class PersonTileState(
    val personKey: String,
    val name: String,
    val entries: List<PersonEntry>,
) {
    val newest: PersonEntry get() = entries.first()
    val count: Int get() = entries.sumOf { it.count }
    val keys: List<String> get() = entries.map { it.key }
    val postTime: Long get() = newest.postTime

    /** True when everything behind the tile is waiting for the next batch delivery. */
    val held: Boolean get() = entries.all { it.heldUntil > 0L }
    val heldUntil: Long get() = entries.maxOf { it.heldUntil }
}

/** Pure grouping of notifications into per-person tiles (JVM-testable). */
object PeopleGrouping {

    /**
     * The identity two notifications must share to land in the same tile. Name-based on purpose:
     * apps rarely attach a stable contact URI, and a person's display name is the one thing a
     * messaging app, a dialer and a mail client agree on. Case and spacing are normalized so
     * "Mikko  Mäkelä" and "mikko mäkelä" merge; punctuation is left alone (initials matter).
     */
    fun personKey(name: String): String = name.trim().lowercase().replace(WHITESPACE, " ")

    /**
     * Groups [entries] by [personKey], sent through [alias] (the user's manual "this is the same
     * person" links: alias key → target key); tiles and their entries are newest first. A merged
     * tile is named after an entry that carries the target's own name when there is one, so a link
     * from "M. Mäkelä" to "Mikko" reads "Mikko".
     */
    fun group(entries: List<PersonEntry>, alias: (String) -> String = { it }): List<PersonTileState> =
        entries
            .filter { it.name.isNotBlank() }
            .groupBy { it.groupKey ?: alias(personKey(it.name)) }
            .map { (key, group) ->
                val sorted = group.sortedByDescending { it.postTime }
                val own = sorted.firstOrNull { personKey(it.name) == key } ?: sorted.first()
                PersonTileState(personKey = key, name = own.name.trim(), entries = sorted)
            }
            .sortedByDescending { it.postTime }

    /** Re-groups already grouped [tiles] with the user's [aliases] (alias key → target key). */
    fun merge(tiles: List<PersonTileState>, aliases: Map<String, String>): List<PersonTileState> =
        if (aliases.isEmpty()) tiles else group(tiles.flatMap { it.entries }) { aliases[it] ?: it }

    private val WHITESPACE = Regex("\\s+")
}
