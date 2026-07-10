package org.arkikeskus.launcher.data

/** Pure slot selection for the built-in notifications widget (JVM-testable). */
object NotificationWidgetLayout {

    /**
     * What a row of [maxSlots] slots shows: every item when they all fit, otherwise the first
     * (newest) `maxSlots - 1` items with the rest folded into an overflow count that occupies
     * the last slot. Returns the items to render and the overflow count (0 = no chip).
     */
    fun <T> select(items: List<T>, maxSlots: Int): Pair<List<T>, Int> = when {
        maxSlots <= 0 -> emptyList<T>() to items.size
        items.size <= maxSlots -> items to 0
        else -> items.take(maxSlots - 1) to (items.size - (maxSlots - 1))
    }
}
