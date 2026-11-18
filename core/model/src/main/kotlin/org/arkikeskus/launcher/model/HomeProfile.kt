package org.arkikeskus.launcher.model

/** What a home profile does with an app installed after the profile was made. */
enum class NewAppsRule(val id: String) {
    /** Shown here wherever it was installed. */
    ALWAYS("always"),

    /** Shown here only when this profile was the one in use at the install. */
    INSTALLED_HERE("here"),

    /** Not shown until the user picks it. */
    NEVER("never"),
    ;

    companion object {
        fun fromId(id: String?): NewAppsRule = entries.firstOrNull { it.id == id } ?: ALWAYS
    }
}

/**
 * A home profile: a named view of the launcher with its own home screen, apps and look. Not an Android
 * user profile (work profile, private space), which the rest of the code calls a profile.
 *
 * An empty [name] is the default name, which the screens show translated. [startMinutes] is the minute of
 * the day the profile takes over by the clock (null = switched to by hand only), on [weekdays]
 * (`java.time.DayOfWeek` values, 1 = Monday; empty = every day).
 */
data class HomeProfile(
    val id: Long,
    val name: String,
    val newApps: NewAppsRule = NewAppsRule.ALWAYS,
    val startMinutes: Int? = null,
    val weekdays: Set<Int> = emptySet(),
) {
    companion object {
        /** The profile every launcher has: the one made of the state before profiles existed. */
        const val FIRST_ID = 1L
        const val MAX_PROFILES = 8
        const val MAX_NAME_LENGTH = 24

        private const val MINUTES_IN_DAY = 24 * 60

        /** A name as it is kept: on one line, no tabs, trimmed and at most [MAX_NAME_LENGTH] long. */
        fun cleanName(raw: String): String =
            raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(MAX_NAME_LENGTH).trim()

        /** One line per profile, the fields apart by tabs: id, name, rule, start minute, weekdays. */
        fun encode(profiles: List<HomeProfile>): String = profiles.joinToString("\n") { p ->
            listOf(
                p.id.toString(),
                cleanName(p.name),
                p.newApps.id,
                p.startMinutes?.toString().orEmpty(),
                p.weekdays.sorted().joinToString(","),
            ).joinToString("\t")
        }

        /**
         * The stored list. Whatever [raw] holds there is at least one profile: nothing stored, or nothing
         * readable, is the one unnamed first profile. A line that cannot be read is skipped, an id stored
         * twice counts once, and the list stops at [MAX_PROFILES].
         */
        fun decode(raw: String?): List<HomeProfile> {
            val profiles = raw.orEmpty().split("\n").mapNotNull(::decodeLine).distinctBy { it.id }.take(MAX_PROFILES)
            return profiles.ifEmpty { listOf(HomeProfile(FIRST_ID, "")) }
        }

        private fun decodeLine(line: String): HomeProfile? {
            val fields = line.split('\t')
            if (fields.size < 2) return null
            val id = fields[0].trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
            return HomeProfile(
                id = id,
                name = cleanName(fields[1]),
                newApps = NewAppsRule.fromId(fields.getOrNull(2)),
                startMinutes = fields.getOrNull(3)?.trim()?.toIntOrNull()?.takeIf { it in 0 until MINUTES_IN_DAY },
                weekdays = fields.getOrNull(4).orEmpty().split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 1..7 }
                    .toSet(),
            )
        }
    }
}
