package org.arkikeskus.launcher.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart

/**
 * A [ProfileSystem] a test steers: [profiles] is what a snapshot answers, [broadcasts] carries the profile
 * broadcasts, [request] answers a quiet-mode request, [collectors] counts how many receivers were registered,
 * [entryHidden] is Android's own "hide the private space when locked" (null: it cannot be read, as on Android 15).
 */
internal open class FakeProfileSystem(var profiles: List<ProfileSnapshot>) : ProfileSystem {
    val broadcasts = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    var collectors = 0
    var request: (Long, Boolean) -> Boolean = { _, _ -> true }
    var entryHidden: (Long) -> Boolean? = { null }
    override fun snapshot(): List<ProfileSnapshot> = profiles

    // The contract: one re-read request as soon as the receiver listens, then the broadcasts.
    override fun changes(): Flow<Unit> = broadcasts.onStart { collectors++; emit(Unit) }
    override fun requestQuietMode(serial: Long, enabled: Boolean): Boolean = request(serial, enabled)
    override fun privateSpaceEntryHidden(serial: Long): Boolean? = entryHidden(serial)
}
