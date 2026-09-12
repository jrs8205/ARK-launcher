package org.arkikeskus.launcher.data

/** Pure slot selection for the built-in notifications widget (JVM-testable). */
object NotificationWidgetLayout {

    /**
     * What a row of [maxSlots] slots shows: every item when they all fit, otherwise the first
     * (newest) `maxSlots - 1` items with the rest folded into an overflow count that occupies
     * the last slot — except a single slot, which keeps the newest item and counts the rest.
     * Returns the items to render and the overflow count (0 = no chip).
     */
    fun <T> select(items: List<T>, maxSlots: Int): Pair<List<T>, Int> = when {
        maxSlots <= 0 -> emptyList<T>() to items.size
        items.size <= maxSlots -> items to 0
        // A single slot must stay actionable: show the newest item and fold the REST into the
        // count — a bare "+N" with nothing tappable would make the whole widget dead.
        maxSlots == 1 -> items.take(1) to (items.size - 1)
        else -> items.take(maxSlots - 1) to (items.size - (maxSlots - 1))
    }

    /**
     * The launcher entry that stands for a package in the widget when the package has several
     * (an activity plus an activity-alias, say): the one the package's launch intent points to,
     * else the first. Picking by launch intent keeps the icon independent of label sort order,
     * which is locale-dependent. [launchClassName] is only resolved when there is a choice.
     */
    fun <T> representative(entries: List<T>, className: (T) -> String, launchClassName: () -> String?): T {
        if (entries.size == 1) return entries.first()
        val launch = launchClassName()
        return entries.firstOrNull { className(it) == launch } ?: entries.first()
    }
}
