package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationWidgetLayoutTest {

    @Test
    fun `everything fits - no overflow`() {
        val (shown, overflow) = NotificationWidgetLayout.select(listOf("a", "b", "c"), maxSlots = 3)
        assertThat(shown).containsExactly("a", "b", "c").inOrder()
        assertThat(overflow).isEqualTo(0)
    }

    @Test
    fun `one too many - the chip takes the last slot`() {
        val (shown, overflow) = NotificationWidgetLayout.select(listOf("a", "b", "c", "d"), maxSlots = 3)
        assertThat(shown).containsExactly("a", "b").inOrder()
        assertThat(overflow).isEqualTo(2)
    }

    @Test
    fun `a single slot with several items keeps the newest item actionable`() {
        val (shown, overflow) = NotificationWidgetLayout.select(listOf("a", "b"), maxSlots = 1)
        assertThat(shown).containsExactly("a")
        assertThat(overflow).isEqualTo(1)
    }

    @Test
    fun `empty input shows nothing`() {
        val (shown, overflow) = NotificationWidgetLayout.select(emptyList<String>(), maxSlots = 4)
        assertThat(shown).isEmpty()
        assertThat(overflow).isEqualTo(0)
    }

    @Test
    fun `zero slots renders nothing but reports the count`() {
        val (shown, overflow) = NotificationWidgetLayout.select(listOf("a"), maxSlots = 0)
        assertThat(shown).isEmpty()
        assertThat(overflow).isEqualTo(1)
    }
}
