package org.arkikeskus.launcher.data

import android.os.Process
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.profile.CountingStore
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #33: "most used" can also count apps that were opened elsewhere than in the launcher (a notification, a
 * widget, another app), from what Android tells an app with usage access. One score for both: an app that came
 * to the front is one launch more, whoever opened it. Read only while the user has switched it on and given the
 * access, from the moment it was switched on, never twice for the same moment, and never for anything the
 * launcher does not show. A launch made in the launcher is in both records (the launcher's own and Android's)
 * and is one launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemUsageSyncTest {
    private val user = Process.myUserHandle()
    private val mail = AppItem("com.mail", "com.mail.Inbox", user, 0L, "Mail")
    private val mailCompose = AppItem("com.mail", "com.mail.Compose", user, 0L, "Write mail")
    private val maps = AppItem("com.maps", "com.maps.Main", user, 0L, "Maps")
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    /** What Android saw, and whether it tells the launcher. */
    private class FakeUsage(var access: Boolean = true) : SystemUsageSource {
        val resumed = ArrayList<AppOpening>()
        val asked = ArrayList<Pair<Long, Long>>()
        override fun hasAccess(): Boolean = access
        override fun resumedBetween(fromMs: Long, toMs: Long): List<AppOpening> {
            asked += fromMs to toMs
            return resumed.filter { it.timeMs in fromMs until toMs }
        }
    }

    private val source = FakeUsage()
    private val store = CountingStore()
    private val usage = AppUsageRepository(store)
    private val settings = SettingsRepository(store)
    private val sync = SystemUsageSync(
        source = source,
        usage = usage,
        enabled = { settings.settings.first().countSystemUsage },
        apps = { listOf(mail, mailCompose, maps) },
        ownPackage = "org.arkikeskus.launcher",
    )

    /** When the user switched the count on in these tests: two days before [now]. */
    private val start = now - 2 * day

    private suspend fun switchOn(at: Long = start) = settings.setCountSystemUsage(true, now = at)

    private fun screen(pkg: String, cls: String, at: Long) = AppOpening(pkg, cls, at)

    private suspend fun scoreOf(app: AppItem, at: Long = now): Float =
        usage.usage.first()[app.key]?.let { AppUsageRepository.currentScore(it, at) } ?: 0f

    // --- what an opening is --------------------------------------------------------------------------------

    @Test fun anAppsOwnScreensInARowAreOneOpeningAndComingBackIsAnother() {
        val seen = listOf(
            screen("com.mail", "com.mail.Inbox", 1), screen("com.mail", "com.mail.Thread", 2),
            screen("org.arkikeskus.launcher", "Home", 3),
            screen("com.mail", "com.mail.Thread", 4),
            screen("com.maps", "com.maps.Main", 5), screen("com.maps", "com.maps.Route", 6),
        )

        assertThat(openingsOf(seen).map { it.packageName to it.timeMs })
            .containsExactly("com.mail" to 1L, "org.arkikeskus.launcher" to 3L, "com.mail" to 4L, "com.maps" to 5L).inOrder()
    }

    @Test fun anOpeningCountsForTheEntryThatCameToTheFrontElseForThePackagesFirst() {
        val apps = listOf(mail, mailCompose, maps)

        // An app with two entries on the launcher: each is counted as itself.
        assertThat(appKeyFor(screen("com.mail", "com.mail.Compose", 1), apps)).isEqualTo(mailCompose.key)
        // A screen deep inside the app (opened from a notification): the package's first entry by class name.
        assertThat(appKeyFor(screen("com.mail", "com.mail.Thread", 1), apps)).isEqualTo(mailCompose.key)
        assertThat(appKeyFor(AppOpening("com.maps", null, 1), apps)).isEqualTo(maps.key)
        // A package the launcher shows no app of (a system dialog, a keyboard): nothing.
        assertThat(appKeyFor(screen("com.android.systemui", "Dialog", 1), apps)).isNull()
    }

    // --- the sync -----------------------------------------------------------------------------------------

    @Test fun whatCameToTheFrontElsewhereIsCountedAsLaunches() = runTest {
        switchOn()
        source.resumed += listOf(
            screen("com.maps", "com.maps.Main", now - 3_600_000),
            screen("org.arkikeskus.launcher", "Home", now - 3_000_000),
            screen("com.maps", "com.maps.Route", now - 2_000_000),
            screen("com.android.systemui", "Dialog", now - 1_500_000),
            screen("com.mail", "com.mail.Inbox", now - 1_000_000),
        )

        sync.sync(now)

        assertThat(scoreOf(maps)).isWithin(0.05f).of(2f)
        assertThat(scoreOf(mail)).isWithin(0.05f).of(1f)
        // The launcher itself and what it shows no app of are in no score.
        assertThat(usage.usage.first().keys).containsExactly(maps.key, mail.key)
        assertThat(usage.systemSyncedTo.first()).isEqualTo(now)
    }

    @Test fun noMomentIsCountedTwice() = runTest {
        switchOn()
        source.resumed += screen("com.maps", "com.maps.Main", now - 3_600_000)
        sync.sync(now)

        source.resumed += screen("com.mail", "com.mail.Inbox", now + 60_000)
        sync.sync(now + 120_000)

        // The second sync asked only for what came after the first.
        assertThat(source.asked.last()).isEqualTo(now to now + 120_000)
        assertThat(scoreOf(maps, now + 120_000)).isWithin(0.05f).of(1f)
        assertThat(scoreOf(mail, now + 120_000)).isWithin(0.05f).of(1f)
    }

    /** A sync after a long pause reads a week at most: what is older has all but faded from the score anyway,
     *  and Android keeps these events for days, not for ever. */
    @Test fun atMostAWeekIsRead() = runTest {
        switchOn(at = now - 30 * day)

        sync.sync(now)

        assertThat(source.asked.single()).isEqualTo(now - 7 * day to now)
    }

    @Test fun withTheSwitchOffOrWithoutAccessNothingIsReadAndNothingCounted() = runTest {
        source.resumed += screen("com.maps", "com.maps.Main", now - 1_000)

        sync.sync(now)
        switchOn()
        source.access = false
        sync.sync(now)

        assertThat(source.asked).isEmpty()
        assertThat(usage.usage.first()).isEmpty()
        // Nothing was read, so nothing counts as read: once the access is given, the count is still from the
        // moment it was switched on.
        assertThat(usage.systemSyncedTo.first()).isEqualTo(start)
    }

    // --- from when ------------------------------------------------------------------------------------------

    /** What was opened before the switch was turned on is not read at all: the launcher's own launches of that
     *  time are in the scores already and could not be told from Android's report of them. */
    @Test fun theCountStartsWhenItIsSwitchedOn() = runTest {
        source.resumed += listOf(
            screen("com.maps", "com.maps.Main", start - 3_600_000),
            screen("com.mail", "com.mail.Inbox", start + 60_000),
        )
        switchOn()

        sync.sync(now)

        assertThat(source.asked.single()).isEqualTo(start to now)
        assertThat(usage.usage.first().keys).containsExactly(mail.key)
    }

    /** The switch is on but no moment was noted for it (something else than the setting's own switch turned it
     *  on): the count starts at that sync, with nothing read back. */
    @Test fun withNoStartNotedTheCountStartsAtTheFirstSync() = runTest {
        store.edit { it[booleanPreferencesKey("count_system_usage")] = true }
        source.resumed += screen("com.maps", "com.maps.Main", now - 1_000)

        sync.sync(now)

        assertThat(source.asked).isEmpty()
        assertThat(usage.usage.first()).isEmpty()
        assertThat(usage.systemSyncedTo.first()).isEqualTo(now)

        source.resumed += screen("com.mail", "com.mail.Inbox", now + 60_000)
        sync.sync(now + 120_000)

        assertThat(usage.usage.first().keys).containsExactly(mail.key)
    }

    @Test fun switchingTheCountOffForgetsItsBookkeeping() = runTest {
        switchOn()
        usage.recordLaunch(maps.key, at = now - 60_000)
        assertThat(usage.ownLaunches.first()).containsExactly(maps.key to now - 60_000)

        settings.setCountSystemUsage(false)

        assertThat(usage.ownLaunches.first()).isEmpty()
        assertThat(usage.systemSyncedTo.first()).isEqualTo(0L)
        // And with the switch off a launch is a score only, as it always was.
        usage.recordLaunch(maps.key, at = now - 30_000)
        assertThat(usage.ownLaunches.first()).isEmpty()
        assertThat(scoreOf(maps)).isWithin(0.05f).of(2f)
    }

    // --- a launch made in the launcher is in both records ------------------------------------------------------

    /** A launch made in the launcher is recorded by the launcher at once, and Android reports the same launch a
     *  moment later: it is one launch. An opening long before the stored one still counts. */
    @Test fun aLaunchTheLauncherRecordedItselfIsNotCountedAgain() = runTest {
        switchOn()
        usage.recordLaunch(maps.key, at = now - 60_000)
        source.resumed += listOf(
            screen("com.maps", "com.maps.Main", now - 3_600_000), // an hour earlier, from a notification
            screen("org.arkikeskus.launcher", "Home", now - 120_000),
            screen("com.maps", "com.maps.Main", now - 59_500), // the launch above, as Android saw it
        )

        sync.sync(now)

        assertThat(scoreOf(maps)).isWithin(0.05f).of(2f)
        // The score's own time did not move back to the older opening.
        assertThat(usage.usage.first().getValue(maps.key).lastUsed).isEqualTo(now - 60_000)
    }

    /** Seen on an emulator: an app opened twice from the dock, the drawer not opened in between, came out as
     *  three launches (only the launch next to the score's own time was known for the launcher's). Every launch
     *  made in the launcher is one launch, however many there are before the drawer opens. */
    @Test fun everyLaunchMadeInTheLauncherIsOneLaunchHoweverManyBeforeTheDrawerOpens() = runTest {
        switchOn()
        usage.recordLaunch(maps.key, at = now - 1_800_000)
        usage.recordLaunch(maps.key, at = now - 900_000)
        source.resumed += listOf(
            screen("com.maps", "com.maps.Main", now - 1_799_600),
            screen("org.arkikeskus.launcher", "Home", now - 1_700_000),
            screen("com.maps", "com.maps.Main", now - 899_600),
        )

        sync.sync(now)

        assertThat(scoreOf(maps)).isWithin(0.05f).of(2f)
        // Both reports found their launch: nothing waits for a third.
        assertThat(usage.ownLaunches.first()).isEmpty()
    }

    /** Seen on an emulator too: an app opened twice from elsewhere within ten seconds came out as one launch
     *  (the second was taken for a report of the first). Only a launch the launcher made itself is left out. */
    @Test fun twoOpeningsElsewhereAFewSecondsApartAreTwo() = runTest {
        switchOn()
        source.resumed += listOf(
            screen("com.maps", "com.maps.Main", now - 60_000),
            screen("org.arkikeskus.launcher", "Home", now - 57_000),
            screen("com.maps", "com.maps.Route", now - 53_000),
        )

        sync.sync(now)

        assertThat(scoreOf(maps)).isWithin(0.05f).of(2f)
    }

    /** Android names the screen that came up, which need not be the entry the launcher started (an entry that
     *  is an alias of another screen): the report is known for the launch by its app, not by its screen. */
    @Test fun aReportIsKnownForTheLaunchersOwnByItsAppNotByItsScreen() = runTest {
        switchOn()
        usage.recordLaunch(mail.key, at = now - 60_000)
        // "com.mail.Main" is no entry: alone it would be counted for the package's first entry, mailCompose.
        source.resumed += screen("com.mail", "com.mail.Main", now - 59_000)

        sync.sync(now)

        assertThat(scoreOf(mail)).isWithin(0.05f).of(1f)
        assertThat(scoreOf(mailCompose)).isEqualTo(0f)
    }

    /** A work profile's app has the personal app's package, and Android tells nothing of it here: its launch
     *  is not what a report of the personal app is taken for. */
    @Test fun aWorkAppsLaunchDoesNotHideAnOpeningOfThePersonalApp() = runTest {
        switchOn()
        usage.recordLaunch("com.maps/com.maps.Main/10", at = now - 60_000)
        source.resumed += screen("com.maps", "com.maps.Main", now - 59_000)

        sync.sync(now)

        assertThat(scoreOf(maps)).isWithin(0.05f).of(1f)
    }

    /** A launch of the very last moment: Android's report of it can fall after the end of this read, and is
     *  then in the next one. The launch is remembered until then. */
    @Test fun aLaunchJustBeforeAReadIsStillKnownInTheNextOne() = runTest {
        switchOn()
        usage.recordLaunch(maps.key, at = now - 200)

        sync.sync(now)
        source.resumed += screen("com.maps", "com.maps.Main", now + 100)
        sync.sync(now + 60_000)

        assertThat(scoreOf(maps, now + 60_000)).isWithin(0.05f).of(1f)
        // By then it is old: forgotten, as is any launch Android never reported (a work profile's app).
        assertThat(usage.ownLaunches.first()).isEmpty()
    }

    /** The launches remembered for the next read are few: past [AppUsageRepository.MAX_OWN_LAUNCHES] the oldest
     *  go (a phone on which the drawer is never opened, or the access never given, must not grow the file). */
    @Test fun theLaunchesRememberedForTheNextReadStayFew() = runTest {
        switchOn()
        repeat(AppUsageRepository.MAX_OWN_LAUNCHES + 50) { usage.recordLaunch(maps.key, at = start + 1_000L * it) }

        val kept = usage.ownLaunches.first()
        assertThat(kept).hasSize(AppUsageRepository.MAX_OWN_LAUNCHES)
        assertThat(kept.last().second).isEqualTo(start + 1_000L * (AppUsageRepository.MAX_OWN_LAUNCHES + 49))

        sync.sync(now)

        assertThat(usage.ownLaunches.first()).isEmpty()
    }
}
