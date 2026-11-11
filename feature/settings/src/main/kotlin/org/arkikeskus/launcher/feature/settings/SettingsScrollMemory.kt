package org.arkikeskus.launcher.feature.settings

/** A place in Settings: the open category's key ([page]; null = the main page) and how far it was scrolled. */
internal class SettingsPlace(val page: String?, val offset: Int)

/**
 * Where Settings was when the user left it, kept for a short while (owner decision 2026-10-31): Settings
 * reopened within [WINDOW_MS] of leaving starts on that page at that place, so a quick look at home and back
 * does not mean finding the place again; later it starts at the top of the main page, as a list opened after
 * a long while should. Process memory only: a fresh process starts from the top. Times are a monotonic clock
 * in milliseconds (SystemClock.elapsedRealtime at the call sites).
 */
internal object SettingsScrollMemory {
    /** How long the place is kept after leaving. */
    const val WINDOW_MS = 5 * 60_000L

    private var page: String? = null
    private var offset = 0
    private var leftAt = 0L

    /** Settings is being left on [page] (null = the main page) at [offset] px, at [now]. */
    fun remember(page: String?, offset: Int, now: Long) {
        this.page = page
        this.offset = offset
        leftAt = now
    }

    /** The place to open at, at [now]: the remembered one while it is fresh, else the top of the main page. */
    fun restore(now: Long): SettingsPlace =
        if (leftAt != 0L && now - leftAt <= WINDOW_MS) SettingsPlace(page, offset) else SettingsPlace(null, 0)

    /** Forget the place (tests). */
    fun clear() {
        page = null
        offset = 0
        leftAt = 0L
    }
}
