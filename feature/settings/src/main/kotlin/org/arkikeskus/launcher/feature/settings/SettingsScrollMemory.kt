package org.arkikeskus.launcher.feature.settings

/**
 * Where the Settings list was when the user left it, kept for a short while (owner decision
 * 2026-10-31): Settings reopened within [WINDOW_MS] of leaving starts at that place, so a quick look
 * at home and back does not mean scrolling the whole list again; later it starts from the top, as a
 * list opened after a long while should. Process memory only: a fresh process starts from the top.
 * Times are a monotonic clock in milliseconds (SystemClock.elapsedRealtime at the call sites).
 */
internal object SettingsScrollMemory {
    /** How long the place is kept after leaving. */
    const val WINDOW_MS = 5 * 60_000L

    private var offset = 0
    private var leftAt = 0L

    /** The list is being left at [offset] px, at [now]. */
    fun remember(offset: Int, now: Long) {
        this.offset = offset
        leftAt = now
    }

    /** The offset to open at, at [now]: the remembered place while it is fresh, else the top. */
    fun restore(now: Long): Int = if (leftAt != 0L && now - leftAt <= WINDOW_MS) offset else 0

    /** Forget the place (tests). */
    fun clear() {
        offset = 0
        leftAt = 0L
    }
}
