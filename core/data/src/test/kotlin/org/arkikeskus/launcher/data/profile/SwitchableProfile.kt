package org.arkikeskus.launcher.data.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The home profile that is on, as a test sets it. [afterRead] runs after each [current], once it is set:
 *  the way to change the profile in the middle of someone's work. */
class SwitchableProfile(start: Long = 1L) : ActiveHomeProfile {
    val state = MutableStateFlow(start)
    var afterRead: (() -> Unit)? = null

    override val id: Flow<Long> = state

    override suspend fun current(): Long = state.value.also { afterRead?.invoke() }
}

/** The home profile that is on, with the hold the real store has: [switchTo] waits for work that needs the
 *  profile to stay ([whileActiveStays]), as [HomeProfileStore.setActive] does. */
class HoldingProfile(start: Long = 1L) : ActiveHomeProfile {
    val state = MutableStateFlow(start)
    private val steady = Mutex()

    override val id: Flow<Long> = state

    override suspend fun current(): Long = state.value

    override suspend fun <T> whileActiveStays(work: suspend (active: Long) -> T): T = steady.withLock { work(state.value) }

    suspend fun switchTo(id: Long) = steady.withLock { state.value = id }
}
