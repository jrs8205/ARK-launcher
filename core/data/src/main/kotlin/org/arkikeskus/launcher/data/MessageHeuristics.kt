package org.arkikeskus.launcher.data

/** Text heuristics the listener applies to notifications before they become people tiles. */
object MessageHeuristics {

    /**
     * True for the generic "Missed call"-style label a dialer puts in whichever field isn't the
     * caller, in the languages a dialer here is likely to use. Whole words only: a caller named
     * Callum or Appelbaum must not read as the label.
     */
    fun looksLikeCallLabel(s: String): Boolean = CALL_LABEL.containsMatchIn(s)

    /**
     * True when [text] reads like a one-time code: a 4–8 digit number next to a code word. Such a
     * message is useless an hour later, so the batch never holds it. Errs on the side of
     * delivering: a false positive only means one message isn't held.
     */
    fun looksLikeOneTimeCode(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return SHORT_NUMBER.containsMatchIn(text) && CODE_WORD.containsMatchIn(text)
    }

    private val CALL_LABEL = Regex(
        """\b(calls?|puhelu[a-z]{0,2}|soitto[a-z]{0,2}|samtal|anrufe?|appels?|llamadas?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SHORT_NUMBER = Regex("""(?<![\d.,])\d{4,8}(?![\d.,])""")
    private val CODE_WORD = Regex(
        """\b(codes?|koodi\w*|otp|verif\w*|vahvist\w*|tunnusluku\w*|passcode|pin|one-time|kertak[äa]ytt\w*|2fa)""",
        RegexOption.IGNORE_CASE,
    )
}
