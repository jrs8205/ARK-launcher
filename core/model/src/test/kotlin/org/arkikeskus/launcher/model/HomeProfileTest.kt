package org.arkikeskus.launcher.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A home profile is a named view of the launcher: its own home screen, apps and look. The list of them is
 * kept as text, one line each, and whatever that text holds the launcher always has at least one profile.
 */
class HomeProfileTest {

    @Test fun aListSurvivesEncodeAndDecode() {
        val list = listOf(
            HomeProfile(1, "Koti ja työ"),
            HomeProfile(3, "Work", NewAppsRule.INSTALLED_HERE, startMinutes = 480, weekdays = setOf(1, 2, 3, 4, 5)),
            HomeProfile(4, "", NewAppsRule.NEVER, startMinutes = 0),
        )

        assertThat(HomeProfile.decode(HomeProfile.encode(list))).isEqualTo(list)
    }

    @Test fun noStoredListIsOneUnnamedFirstProfile() {
        assertThat(HomeProfile.decode(null)).containsExactly(HomeProfile(1, ""))
        assertThat(HomeProfile.decode("")).containsExactly(HomeProfile(1, ""))
        assertThat(HomeProfile.decode(" \n ")).containsExactly(HomeProfile(1, ""))
    }

    /**
     * A name is cut where a character ends, never inside one. Cut by code units, a name that ran over the
     * limit in the middle of an emoji kept half of it (a lone surrogate, shown as a box and not valid text to
     * store), and a letter lost the mark written after it.
     */
    @Test fun aNameIsCutBetweenCharactersNeverInsideOne() {
        val x23 = "x".repeat(23)
        val grin = "😀" // one emoji, two code units
        val finland = "🇫🇮" // one flag, four
        val family = "👨‍👩‍👧" // one emoji of three joined, eight
        val eAcute = "é" // one letter with its mark, two

        // The limit falls inside the emoji: the whole emoji goes.
        assertThat(HomeProfile.fitName(x23 + grin + "y")).isEqualTo(x23)
        assertThat(HomeProfile.fitName("x".repeat(22) + finland)).isEqualTo("x".repeat(22))
        assertThat(HomeProfile.fitName("x".repeat(20) + family)).isEqualTo("x".repeat(20))
        assertThat(HomeProfile.fitName(x23 + eAcute)).isEqualTo(x23)
        // A character that ends at the limit stays whole.
        assertThat(HomeProfile.fitName("x".repeat(22) + grin + "y")).isEqualTo("x".repeat(22) + grin)
        assertThat(HomeProfile.fitName("x".repeat(22) + eAcute + "y")).isEqualTo("x".repeat(22) + eAcute)
        // A name within the limit is not touched.
        assertThat(HomeProfile.fitName("Work $grin")).isEqualTo("Work $grin")
        // The kept name goes through the same cut.
        assertThat(HomeProfile.cleanName(x23 + grin + "y")).isEqualTo(x23)
        assertThat(HomeProfile.cleanName("x".repeat(40))).isEqualTo("x".repeat(24))
    }

    @Test fun aNameIsCutToItsLimitAndLosesTabsAndNewlines() {
        assertThat(HomeProfile.cleanName("  Wo\trk\nplace  ")).isEqualTo("Wo rk place")
        assertThat(HomeProfile.cleanName("x".repeat(40))).hasLength(HomeProfile.MAX_NAME_LENGTH)
        // The stored text is a line per profile with tabs between the fields: a name can break neither.
        val stored = HomeProfile.encode(listOf(HomeProfile(1, "a\tb\nc")))
        assertThat(stored.lines()).hasSize(1)
        assertThat(HomeProfile.decode(stored)).containsExactly(HomeProfile(1, "a b c"))
    }

    @Test fun aBrokenLineIsSkippedAndAnEmptyResultFallsBackToTheFirstProfile() {
        assertThat(HomeProfile.decode("garbage\n2\tWork\talways\t\t")).containsExactly(HomeProfile(2, "Work"))
        assertThat(HomeProfile.decode("garbage\nmore\n0\tZero\n-4\tMinus")).containsExactly(HomeProfile(1, ""))
    }

    @Test fun anUnknownRuleIsAlways() {
        assertThat(HomeProfile.decode("2\tWork\tsomething\t\t").single().newApps).isEqualTo(NewAppsRule.ALWAYS)
        assertThat(NewAppsRule.fromId(null)).isEqualTo(NewAppsRule.ALWAYS)
        assertThat(NewAppsRule.fromId("here")).isEqualTo(NewAppsRule.INSTALLED_HERE)
        assertThat(NewAppsRule.fromId("never")).isEqualTo(NewAppsRule.NEVER)
    }

    @Test fun aStartOutsideTheDayAndAWeekdayOutsideTheWeekAreDropped() {
        val profile = HomeProfile.decode("2\tWork\talways\t1440\t0,1,7,8,x").single()

        assertThat(profile.startMinutes).isNull()
        assertThat(profile.weekdays).containsExactly(1, 7)
    }

    @Test fun anIdStoredTwiceIsOneProfileAndTheListStopsAtItsLimit() {
        assertThat(HomeProfile.decode("2\tWork\n2\tAgain").map { it.name }).containsExactly("Work")

        val many = (1L..12L).joinToString("\n") { "$it\tP$it" }
        assertThat(HomeProfile.decode(many)).hasSize(HomeProfile.MAX_PROFILES)
    }
}
