package org.arkikeskus.launcher.data

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.drawable.Icon

/** What a person's notification is about; decides the tile's icon and its quick actions. */
enum class PersonEventKind { MESSAGE, MISSED_CALL, EMAIL }

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
    /** The sender or conversation display name as the app posted it. */
    val name: String,
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
    /** The sender's avatar when the app attached a Person icon. */
    val personIcon: Icon? = null,
    /** The sender's Person.uri (a `tel:` or contact URI) when the app attached one. */
    val personUri: String? = null,
)

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

    /** Groups [entries] by [personKey]; tiles and their entries are newest first. */
    fun group(entries: List<PersonEntry>): List<PersonTileState> =
        entries
            .filter { it.name.isNotBlank() }
            .groupBy { personKey(it.name) }
            .map { (key, group) ->
                val sorted = group.sortedByDescending { it.postTime }
                PersonTileState(personKey = key, name = sorted.first().name.trim(), entries = sorted)
            }
            .sortedByDescending { it.postTime }

    private val WHITESPACE = Regex("\\s+")
}
