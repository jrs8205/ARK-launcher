package org.arkikeskus.launcher.data.search

import android.os.Process
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Discussion #34: the search can also find the apps the user has hidden. Only when asked to: an app is often
 * hidden so that nobody comes across it, and by default a search does not come across it either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSearchHiddenTest {
    private val user = Process.myUserHandle()
    private val bank = AppItem("com.bank", "com.bank.Main", user, 0L, "Bank")
    private val mail = AppItem("com.mail", "com.mail.Main", user, 0L, "Mail")
    private val maps = AppItem("com.maps", "com.maps.Main", user, 0L, "Maps")
    private val apps = listOf(bank, mail, maps)
    private val hidden = setOf(bank.key, maps.key)

    @Test fun hiddenAppsStayOutOfTheResultsUnlessTheSearchIsToFindThem() {
        assertThat(matchingApps(apps, "ba", hidden, findHidden = false)).isEmpty()
        assertThat(matchingApps(apps, "ma", hidden, findHidden = false)).containsExactly(mail)

        assertThat(matchingApps(apps, "ba", hidden, findHidden = true)).containsExactly(bank)
        assertThat(matchingApps(apps, "ma", hidden, findHidden = true)).containsExactly(mail, maps).inOrder()
    }

    /** What matches is as it was: the name has the query in it, capitals and the spaces around the query aside. */
    @Test fun theQueryIsMatchedInTheNameCapitalsAndOuterSpacesAside() {
        assertThat(matchingApps(apps, "  MAIL ", emptySet(), findHidden = false)).containsExactly(mail)
        assertThat(matchingApps(apps, "x", emptySet(), findHidden = true)).isEmpty()
    }
}
