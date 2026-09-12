package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationWidgetRepresentativeTest {

    private data class Entry(val label: String, val className: String)

    private val phone = Entry("Puhelin", "org.example.MainActivity")
    private val messages = Entry("Viestit", "org.example.MessagesLauncher")

    @Test
    fun `the launch activity represents the package regardless of list order`() {
        val picked = NotificationWidgetLayout.representative(
            listOf(messages, phone),
            className = { it.className },
            launchClassName = { "org.example.MainActivity" },
        )
        assertThat(picked).isEqualTo(phone)
    }

    @Test
    fun `without a launch activity the first entry stands in`() {
        val picked = NotificationWidgetLayout.representative(
            listOf(phone, messages),
            className = { it.className },
            launchClassName = { null },
        )
        assertThat(picked).isEqualTo(phone)
    }

    @Test
    fun `an unknown launch class falls back to the first entry`() {
        val picked = NotificationWidgetLayout.representative(
            listOf(messages, phone),
            className = { it.className },
            launchClassName = { "org.example.Gone" },
        )
        assertThat(picked).isEqualTo(messages)
    }

    @Test
    fun `a single entry never resolves the launch intent`() {
        val picked = NotificationWidgetLayout.representative(
            listOf(phone),
            className = { it.className },
            launchClassName = { error("must not be called") },
        )
        assertThat(picked).isEqualTo(phone)
    }
}
