package org.arkikeskus.launcher.data.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The home profile that is on, as a test sets it. [afterRead] runs after each [current], once it is set:
 *  the way to change the profile in the middle of someone's work. */
class SwitchableProfile(start: Long = 1L) : ActiveHomeProfile {
    val state = MutableStateFlow(start)
    var afterRead: (() -> Unit)? = null

    override val id: Flow<Long> = state

    override suspend fun current(): Long = state.value.also { afterRead?.invoke() }
}
