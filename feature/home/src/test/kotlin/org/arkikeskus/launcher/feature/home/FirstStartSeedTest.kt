package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.os.Looper
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the home screen's first start puts on a fresh install: the default clock (and dock), once for the
 * install. It is the ORIGINAL home screen's, the first profile's, whichever profile is on when the home screen
 * first starts.
 *
 * Review round 16. Settings can be opened before the home screen ever starts (Android's screensaver settings
 * lead there), and a profile can be made and switched to in it. The seed went to whichever profile was on:
 * the profile the user had made as an empty one got the clock, the first profile stayed empty, and the seed
 * was marked done for the install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FirstStartSeedTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test fun theFirstStartSeedsTheFirstProfileAlsoWhenAnotherOneIsOn() = runTest {
        // The seed waits for the app list, so there is an app.
        installFakeApp(context, "com.mail", "Mail")
        val wiring = ProductionWiring(context, this)
        try {
            val empty = (wiring.homeProfiles.create("Empty", copyOf = null) as HomeProfileRepository.Created.Done).profile
            assertThat(wiring.homeProfiles.switchTo(empty.id)).isTrue()

            val viewModel = wiring.firstStartHomeViewModel()
            awaitUntil("the seed") { wiring.settingsRepository.defaultLayoutSeededOnce() }

            assertThat(wiring.rowsOfProfile(empty.id)).isEmpty()
            assertThat(wiring.rowsOfProfile(1).map { it.builtinType }).containsExactly(HomeItemEntity.BUILTIN_SMARTSPACE)
            // And the profile that is on is still the one the user chose.
            assertThat(wiring.dao.resolvedProfile()).isEqualTo(empty.id)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /** With the first profile gone before the home screen first started, there is no original home screen
     *  to seed: the profiles there are were made by the user, as they are, and none of them is filled. */
    @Test fun aFirstStartWithoutTheFirstProfileSeedsNoProfile() = runTest {
        installFakeApp(context, "com.mail", "Mail")
        val wiring = ProductionWiring(context, this)
        try {
            val own = (wiring.homeProfiles.create("Mine", copyOf = null) as HomeProfileRepository.Created.Done).profile
            assertThat(wiring.homeProfiles.switchTo(own.id)).isTrue()
            assertThat(wiring.homeProfiles.delete(1)).isEqualTo(HomeProfileRepository.Deleted.Done)

            val viewModel = wiring.firstStartHomeViewModel()
            awaitUntil("the seed to be done with") { wiring.settingsRepository.defaultLayoutSeededOnce() }

            assertThat(wiring.rowsOfProfile(own.id)).isEmpty()
            assertThat(wiring.rowsOfProfile(1)).isEmpty()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /** [packageName]'s launcher activity is what Android opens for its settings: one of the five apps the
     *  default dock is made of. */
    private fun isTheDefaultSettingsApp(packageName: String) {
        val handler = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { this.packageName = packageName; name = "$packageName.Main" }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_SETTINGS), handler)
    }

    /** The dock is the first profile's as the clock is: with another profile on, its row is the first one's. */
    @Test fun theFirstStartSeedsTheDockOfTheFirstProfileToo() = runTest {
        installFakeApp(context, "com.mail", "Mail")
        isTheDefaultSettingsApp("com.mail")
        val wiring = ProductionWiring(context, this)
        try {
            val empty = (wiring.homeProfiles.create("Empty", copyOf = null) as HomeProfileRepository.Created.Done).profile
            assertThat(wiring.homeProfiles.switchTo(empty.id)).isTrue()

            val viewModel = wiring.firstStartHomeViewModel()
            awaitUntil("the seed") { wiring.settingsRepository.defaultLayoutSeededOnce() }

            assertThat(wiring.rowsOfProfile(empty.id)).isEmpty()
            assertThat(wiring.rowsOfProfile(1).filter { it.containerId == HomeItemEntity.DOCK }.map { it.packageName })
                .containsExactly("com.mail")
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 17. Whether there is a first profile was looked at once, before the seed, and the seed
     * then waits for the app list. A first profile deleted in Settings during that wait still got the dock's
     * rows: rows under an id the list no longer has, which show in no profile and which the next restore
     * finds as a home screen nobody is listed for. The look and the rows are now one step, under the hold a
     * deletion takes; the app list is waited for before it.
     */
    @Test fun aFirstProfileDeletedWhileTheSeedWaitsForTheAppListGetsNoRows() = runTest {
        // No app yet: the seed will wait for the app list.
        val wiring = ProductionWiring(context, this)
        try {
            val own = (wiring.homeProfiles.create("Mine", copyOf = null) as HomeProfileRepository.Created.Done).profile
            val viewModel = wiring.firstStartHomeViewModel()
            // The seed has put the clock on the first profile and waits for the app list. (Waited for here:
            // the seed's steps hold the profile, and its coroutine goes on only when this test turns the
            // main looper, so a switch asked for in the middle of one would wait for ever in a test.)
            awaitUntil("the clock") { wiring.rowsOfProfile(1).isNotEmpty() }
            repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

            // In Settings meanwhile: the other profile comes on, and the first one is deleted.
            assertThat(wiring.homeProfiles.switchTo(own.id)).isTrue()
            assertThat(wiring.homeProfiles.delete(1)).isEqualTo(HomeProfileRepository.Deleted.Done)
            // And the app list arrives, with an app the default dock is made of.
            installFakeApp(context, "com.mail", "Mail")
            isTheDefaultSettingsApp("com.mail")
            shadowOf(context.getSystemService(LauncherApps::class.java)).notifyPackageAdded("com.mail")
            awaitUntil("the seed to be done with") { wiring.settingsRepository.defaultLayoutSeededOnce() }

            assertThat(wiring.rowsOfProfile(1)).isEmpty()
            assertThat(wiring.rowsOfProfile(own.id)).isEmpty()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * And without any race: the dock a version before 0.9.7 kept as a list of keys is turned into rows at a
     * start, and that was done for the first profile without a look at whether there is one. With the first
     * profile deleted before the home screen first started, its rows were made under the id that was gone.
     * There is no home screen for that dock any more: no rows are made, and the old key is dropped.
     */
    @Test fun anOlderVersionsDockIsNotTurnedIntoRowsOfAFirstProfileThatIsGone() = runTest {
        installFakeApp(context, "com.mail", "Mail")
        val wiring = ProductionWiring(context, this)
        try {
            wiring.settingsRepository.importRaw(mapOf("dock_favorites" to "com.mail/com.mail.Main/0"))
            val own = (wiring.homeProfiles.create("Mine", copyOf = null) as HomeProfileRepository.Created.Done).profile
            assertThat(wiring.homeProfiles.switchTo(own.id)).isTrue()
            assertThat(wiring.homeProfiles.delete(1)).isEqualTo(HomeProfileRepository.Deleted.Done)

            val viewModel = wiring.firstStartHomeViewModel()
            awaitUntil("the seed to be done with") { wiring.settingsRepository.defaultLayoutSeededOnce() }

            assertThat(wiring.rowsOfProfile(1)).isEmpty()
            assertThat(wiring.rowsOfProfile(own.id)).isEmpty()
            assertThat(wiring.settingsRepository.legacyDockFavorites()).isEmpty()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /** The launcher as it always was, with its one profile: the first start seeds that profile. */
    @Test fun theFirstStartOfALauncherWithOneProfileSeedsIt() = runTest {
        installFakeApp(context, "com.mail", "Mail")
        val wiring = ProductionWiring(context, this)
        try {
            val viewModel = wiring.firstStartHomeViewModel()
            awaitUntil("the seed") { wiring.settingsRepository.defaultLayoutSeededOnce() }

            assertThat(wiring.rowsOfProfile(1).map { it.builtinType }).containsExactly(HomeItemEntity.BUILTIN_SMARTSPACE)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /** The ViewModel's work resumes on the main looper after each storage read, so idle it until [done]. */
    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
