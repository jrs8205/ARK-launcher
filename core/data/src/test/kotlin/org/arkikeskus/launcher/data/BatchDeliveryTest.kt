package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BatchDeliveryTest {
    private val now = 1_000_000L
    private val deadline = now + 3_600_000L
    private val message = PersonEntry(
        key = "chat", name = "Anna", text = "Hello", postTime = now,
        packageName = "chat.app", userSerial = 0, kind = PersonEventKind.MESSAGE,
    )

    private fun hold(entry: PersonEntry? = message, previous: Long? = null, vips: Set<String> = emptySet()) =
        BatchDelivery.holdUntil(entry, previous, now, true, vips, deadline)

    @Test fun `new message on held key bypasses after sender is pinned`() {
        val held = hold()
        assertThat(held).isEqualTo(deadline)
        assertThat(hold(message.copy(text = "New message"), held, setOf("anna"))).isNull()
    }

    @Test fun `code update to a held key bypasses even with old post time`() {
        val held = hold()
        assertThat(hold(message.copy(text = "Your verification code is 123456", postTime = 1), held)).isNull()
    }

    @Test fun `mail code in subject bypasses with a code-free body`() {
        assertThat(hold(message.copy(
            kind = PersonEventKind.EMAIL, title = "Your verification code is 123456",
            text = "This code expires in 10 minutes",
        ))).isNull()
    }

    @Test fun `ordinary updates retain their original delivery time`() {
        assertThat(hold(previous = now + 120_000L)).isEqualTo(now + 120_000L)
    }

    @Test fun `delivery is never held for another cycle`() {
        assertThat(hold(previous = now)).isNull()
        assertThat(hold(message.copy(postTime = now - 100_000L))).isNull()
    }

    @Test fun `missed calls empty incoming messages and disabled batching are released`() {
        assertThat(hold(message.copy(kind = PersonEventKind.MISSED_CALL), deadline)).isNull()
        assertThat(hold(null, deadline)).isNull()
        assertThat(BatchDelivery.holdUntil(message, deadline, now, false, emptySet(), deadline)).isNull()
    }
}
