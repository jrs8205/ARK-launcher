package org.arkikeskus.launcher.data

/** Decides whether a newly posted notification waits in the launcher. Never snoozes Android. */
object BatchDelivery {
    fun holdUntil(
        entry: PersonEntry?,
        previousUntil: Long?,
        now: Long,
        enabled: Boolean,
        vipKeys: Set<String>,
        nextDelivery: Long?,
    ): Long? {
        if (!enabled || entry == null) return null
        if (entry.kind != PersonEventKind.MESSAGE && entry.kind != PersonEventKind.EMAIL) return null
        if (PeopleGrouping.personKey(entry.name) in vipKeys) return null
        if (MessageHeuristics.looksLikeOneTimeCode(listOfNotNull(entry.title, entry.text).joinToString("\n"))) return null
        // Updates retain the original deadline, but the exceptions above apply to each new post.
        if (previousUntil != null) return previousUntil.takeIf { it > now }
        if (now - entry.postTime > 90_000L) return null
        return nextDelivery?.takeIf { it - now >= 60_000L }
    }
}
