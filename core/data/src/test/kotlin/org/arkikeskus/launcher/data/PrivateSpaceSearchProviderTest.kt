package org.arkikeskus.launcher.data

import android.app.Application
import android.os.UserHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.search.PrivateSpaceSearchProvider
import org.arkikeskus.launcher.data.search.SearchAggregator
import org.arkikeskus.launcher.data.search.SearchProvider
import org.arkikeskus.launcher.data.search.privateSpaceEntryMatches
import org.arkikeskus.launcher.data.search.privateSpaceEntryOffered
import org.arkikeskus.launcher.data.search.privateSpaceNames
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.SearchResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The private space in the drawer's search. Its apps are found only while it is unlocked, each flagged as
 * private. And the space itself has a result row, the way in: offered for the first letters of its name, but
 * only for the whole name while the space is locked and its entry is to stay out of sight, so that a casual
 * search does not give it away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PrivateSpaceSearchProviderTest {
    /** What the string resource holds in the phone's language. */
    private var localizedNames = "private space"
    private val own = ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)
    private fun space(locked: Boolean) = ProfileSnapshot(11, ProfileKind.Private, badged = false, paused = locked)
    private val user: UserHandle = UserHandle.getUserHandleForUid(11 * 100_000)
    private val bank = AppItem("com.bank", "com.bank.Main", user, 11, "Bank")
    private val notes = AppItem("com.notes", "com.notes.Main", user, 11, "Notes")
    private val hidePreference = MutableStateFlow<Boolean?>(false)

    private fun TestScope.provider(vararg profiles: ProfileSnapshot): PrivateSpaceSearchProvider {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fake = FakeProfileSystem(profiles.toList())
        val repository = PrivateSpaceRepository(
            system = fake,
            changes = fake.changes(),
            scannedApps = MutableStateFlow(listOf(bank, notes)),
            start = { _, _, _ -> Result.success(Unit) },
            hidePreference = hidePreference,
            scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher),
            io = dispatcher,
            transitionTimeoutMs = 5_000,
        )
        return PrivateSpaceSearchProvider(repository) { localizedNames }
    }

    private val entry = SearchResult.PrivateSpaceEntry

    @Test fun withoutAPrivateSpaceNothingIsEverFound() = runTest {
        val provider = provider(own)

        assertThat(provider.isEnabled()).isFalse()
        assertThat(provider.query("private space")).isEmpty()
        assertThat(provider.query("bank")).isEmpty()
    }

    @Test fun aLockedSpacesAppsAreNotFound() = runTest {
        val provider = provider(own, space(locked = true))

        assertThat(provider.isEnabled()).isTrue()
        assertThat(provider.query("bank")).isEmpty()
        assertThat(provider.query("notes")).isEmpty()
    }

    @Test fun anUnlockedSpacesAppsAreFoundAndFlaggedPrivate() = runTest {
        val provider = provider(own, space(locked = false))

        assertThat(provider.query("ban")).containsExactly(SearchResult.App(bank, privateProfile = true))
        assertThat(provider.query("NOTES")).containsExactly(SearchResult.App(notes, privateProfile = true))
        assertThat(provider.query("o")).containsExactly(SearchResult.App(notes, privateProfile = true))
    }

    @Test fun theSpacesOwnRowIsOfferedForTheFirstLettersOfItsName() = runTest {
        val provider = provider(own, space(locked = true))

        assertThat(provider.query("pri")).containsExactly(entry)
        assertThat(provider.query("Private sp")).containsExactly(entry)
        assertThat(provider.query("private space")).containsExactly(entry)
        // Two letters are any word's beginning, and the middle of the name is no beginning.
        assertThat(provider.query("pr")).isEmpty()
        assertThat(provider.query("space")).isEmpty()
    }

    @Test fun aHiddenLockedSpacesRowIsOfferedOnlyForItsWholeName() = runTest {
        hidePreference.value = true
        val provider = provider(own, space(locked = true))

        assertThat(provider.query("pri")).isEmpty()
        assertThat(provider.query("private spac")).isEmpty()
        assertThat(provider.query("private space")).containsExactly(entry)
        assertThat(provider.query("  Private Space ")).containsExactly(entry)
    }

    @Test fun onceUnlockedAHiddenSpacesRowIsOfferedLikeAnyOther() = runTest {
        hidePreference.value = true
        val provider = provider(own, space(locked = false))

        assertThat(provider.query("pri")).containsExactly(entry)
    }

    @Test fun theNameIsThePhonesLanguagesOrTheEnglishOne() = runTest {
        localizedNames = "yksityinen tila"
        val provider = provider(own, space(locked = true))

        assertThat(provider.query("yksityinen tila")).containsExactly(entry)
        assertThat(provider.query("yks")).containsExactly(entry)
        assertThat(provider.query("private space")).containsExactly(entry)
    }

    /** The rule the screen holds a row against at once when the space changes, before any search has answered. */
    @Test fun theRowIsOfferedOnlyAsTheSpaceStandsNow() {
        fun space(locked: Boolean, hidden: Boolean) = PrivateSpace(userSerial = 11, locked = locked, hiddenWhenLocked = hidden)
        val names = "yksityinen tila"
        assertThat(privateSpaceEntryOffered("private sp", null, names)).isFalse()
        assertThat(privateSpaceEntryOffered("private sp", space(locked = false, hidden = true), names)).isTrue()
        // It locks with hiding on: the same letters no longer earn the row, the whole name still does.
        assertThat(privateSpaceEntryOffered("private sp", space(locked = true, hidden = true), names)).isFalse()
        assertThat(privateSpaceEntryOffered("private space", space(locked = true, hidden = true), names)).isTrue()
        assertThat(privateSpaceEntryOffered("yksityinen tila", space(locked = true, hidden = true), names)).isTrue()
        assertThat(privateSpaceEntryOffered("private sp", space(locked = true, hidden = false), names)).isTrue()
        assertThat(privateSpaceEntryOffered("mail", space(locked = false, hidden = false), names)).isFalse()
    }

    @Test fun theNamesAreTheLanguagesOwnAndTheEnglishOne() {
        assertThat(privateSpaceNames("yksityinen tila")).containsExactly("yksityinen tila", "private space")
        assertThat(privateSpaceNames("vertrauliches profil | privater bereich"))
            .containsExactly("vertrauliches profil", "privater bereich", "private space")
        assertThat(privateSpaceNames("private space")).containsExactly("private space")
        assertThat(privateSpaceNames("")).containsExactly("private space")
    }

    @Test fun theMatchingRuleItself() {
        val names = listOf("private space", "yksityinen tila")
        assertThat(privateSpaceEntryMatches("pri", names, wholeNameOnly = false)).isTrue()
        assertThat(privateSpaceEntryMatches("YKSITYINEN", names, wholeNameOnly = false)).isTrue()
        assertThat(privateSpaceEntryMatches("pr", names, wholeNameOnly = false)).isFalse()
        assertThat(privateSpaceEntryMatches("private spaces", names, wholeNameOnly = false)).isFalse()
        assertThat(privateSpaceEntryMatches("yksityinen tila", names, wholeNameOnly = true)).isTrue()
        assertThat(privateSpaceEntryMatches("yksityinen til", names, wholeNameOnly = true)).isFalse()
        assertThat(privateSpaceEntryMatches("", names, wholeNameOnly = false)).isFalse()
    }

    @Test fun theAggregatorKeepsPrivateResultsApartFromTheOthers() = runTest {
        val mail = AppItem("com.mail", "com.mail.Main", android.os.Process.myUserHandle(), 0, "Mail")
        val general = object : SearchProvider {
            override suspend fun isEnabled() = true
            override suspend fun query(query: String): List<SearchResult> = listOf(SearchResult.App(mail))
        }
        hidePreference.value = false
        val private = provider(own, space(locked = false))

        val results = SearchAggregator(setOf(general, private)).search("private space")
        assertThat(results.apps).containsExactly(mail)
        assertThat(results.privateApps).isEmpty()
        assertThat(results.privateSpaceEntry).isTrue()

        val more = SearchAggregator(setOf(general, private)).search("bank")
        assertThat(more.apps).containsExactly(mail)
        assertThat(more.privateApps).containsExactly(bank)
        assertThat(more.privateSpaceEntry).isFalse()
    }
}
