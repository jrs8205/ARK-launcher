package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageHeuristicsTest {

    @Test
    fun `call label matches the generic dialer strings in several languages`() {
        listOf("Missed call", "2 missed calls", "Vastaamaton puhelu", "Vastaamattomat puhelut", "Vastaamaton soitto",
            "Missat samtal", "Verpasster Anruf", "Appel manqué", "Llamada perdida").forEach {
            assertThat(MessageHeuristics.looksLikeCallLabel(it)).isTrue()
        }
    }

    @Test
    fun `call label does not match a caller whose name merely contains the word`() {
        listOf("Callum Reid", "Anna Callaghan", "Marcello", "Appelbaum", "Puhelinmyyjä").forEach {
            assertThat(MessageHeuristics.looksLikeCallLabel(it)).isFalse()
        }
    }

    @Test
    fun `one-time codes are recognised by a short number next to a code word`() {
        listOf(
            "Your verification code is 482913",
            "Vahvistuskoodisi: 1234",
            "G-583920 is your Google verification code",
            "Use 90210 as your OTP. Do not share it.",
            "Kertakäyttöinen tunnuslukusi on 7781",
        ).forEach { assertThat(MessageHeuristics.looksLikeOneTimeCode(it)).isTrue() }
    }

    @Test
    fun `ordinary messages with numbers are not one-time codes`() {
        listOf(
            "Nähdään klo 1730 asemalla",
            "Order 48213 has shipped",
            "Call me at 555 0123",
            "Koodi on rikki taas :(",
            null, "",
        ).forEach { assertThat(MessageHeuristics.looksLikeOneTimeCode(it)).isFalse() }
    }
}
