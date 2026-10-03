package org.arkikeskus.launcher.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises every change to the system's pinned-shortcut sets together with the home rows that
 * mirror them. `LauncherApps.pinShortcuts` replaces a package's whole set, so a pin, an unpin and a
 * restore's re-pin that interleave can each overwrite another's result — a shortcut pinned during
 * a restore stayed on home with its system pin gone.
 */
object ShortcutPinLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
