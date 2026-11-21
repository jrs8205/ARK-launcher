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

        /** While set, Android does not answer (its service threw, or the user is still locked). */
        var fails = false
        override fun hasAccess(): Boolean = access
        override fun resumedBetween(fromMs: Long, toMs: Long): List<AppOpening>? {
            asked += fromMs to toMs
            return if (fails) null else resumed.filter { it.timeMs in fromMs until toMs }
        }
    }

    private val source = FakeUsage()
    private val store = CountingStore()
    private val usage = AppUsageRepository(store)
    private val settings = SettingsRepository(store)

    /** The launcher's own user's apps as they can be told now; null when they cannot. */
    private var known: List<AppItem>? = listOf(mail, mailCompose, maps)

    /** Runs once, while a sync is reading: after it has read where the count stands, before it writes. */
    private var duringRead: (suspend () -> Unit)? = null
    private val sync = SystemUsageSync(
        source = source,
        usage = usage,
        apps = {
            duringRead?.also { duringRead = null }?.invoke()
            known
        },
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

    /** An app opened twice from elsewhere within ten seconds is two launches (the second used to be taken for
     *  a report of the first). Only a launch the launcher made itself is left out. */
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

    /** Seen on an emulator: an app that asked for a permission as it opened came to the front twice within a
     *  second, the phone's permission dialog between. A screen of something the launcher shows no app of (a
     *  dialog of the phone's own, the share sheet, the recents screen) is not another app in front: the app
     *  under it was opened once. */
    @Test fun aScreenOfNoAppOverAnAppIsNotAnotherAppInFront() = runTest {
        switchOn()
        source.resumed += listOf(
            screen("com.mail", "com.mail.Inbox", now - 60_000),
            screen("com.android.permissioncontroller", "GrantPermissionsActivity", now - 59_500),
            screen("com.mail", "com.mail.Inbox", now - 58_000),
            screen("org.arkikeskus.launcher", "Home", now - 50_000),
            // Left for the recents screen and came back to the same app: no other app was in front either.
            screen("com.maps", "com.maps.Main", now - 40_000),
            screen("com.android.recents", "RecentsActivity", now - 30_000),
            screen("com.maps", "com.maps.Main", now - 28_000),
        )

        sync.sync(now)

        assertThat(scoreOf(mail)).isWithin(0.05f).of(1f)
        assertThat(scoreOf(maps)).isWithin(0.05f).of(1f)
    }

    /** The same app started in the launcher: its one launch answers for its one opening, and the second coming
     *  to the front from under the dialog is not left over as an opening from elsewhere. */
    @Test fun aLaunchInTheLauncherOfAnAppThatShowsADialogOfThePhonesIsOneLaunch() = runTest {
        switchOn()
        usage.recordLaunch(mail.key, at = now - 60_300)
        source.resumed += listOf(
            screen("com.mail", "com.mail.Inbox", now - 60_000),
            screen("com.android.permissioncontroller", "GrantPermissionsActivity", now - 59_500),
            screen("com.mail", "com.mail.Inbox", now - 58_000),
        )

        sync.sync(now)

        assertThat(scoreOf(mail)).isWithin(0.05f).of(1f)
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

    // --- a read is of one count, and only a read that was made moves it on ----------------------------------

    /** The switch was turned off while Android's record was being read: that read is of a count that is no
     *  longer on. Nothing of it is stored: no score, and no time for a count that is off. */
    @Test fun aReadUnderWayWhenTheCountIsSwitchedOffStoresNothing() = runTest {
        switchOn()
        source.resumed += screen("com.maps", "com.maps.Main", now - 60_000)
        duringRead = { settings.setCountSystemUsage(false) }

        sync.sync(now)

        assertThat(usage.usage.first()).isEmpty()
        assertThat(usage.systemSyncedTo.first()).isEqualTo(0L)
        assertThat(usage.systemCount.first()).isNull()
        // And Android was not even asked: with the switch off nothing is READ, not only nothing kept.
        assertThat(source.asked).isEmpty()
    }

    /** Off and on again while the record was being read: the count that is on now started at that moment.
     *  The read was made for the earlier one, and neither its openings nor its time replace the new start. */
    @Test fun aReadUnderWayWhenTheCountIsSwitchedOffAndOnAgainIsNotOfTheNewCount() = runTest {
        switchOn()
        source.resumed += screen("com.maps", "com.maps.Main", now - 60_000)
        duringRead = {
            settings.setCountSystemUsage(false)
            settings.setCountSystemUsage(true, now = now + 5_000)
        }

        sync.sync(now)

        assertThat(usage.usage.first()).isEmpty()
        assertThat(usage.systemSyncedTo.first()).isEqualTo(now + 5_000)
        // The span asked for was the earlier count's: it is not asked for at all.
        assertThat(source.asked).isEmpty()
    }

    /** The phone's clock was a week ahead when the record was last read, and has been put right since: the
     *  time read up to is a week in the future. The reading starts anew at this moment, or every read would
     *  wait a week for that time to come again while apps go on being opened. */
    @Test fun aClockSetBackStartsTheReadingAnew() = runTest {
        switchOn()
        sync.sync(now + 7 * day)
        assertThat(usage.systemSyncedTo.first()).isEqualTo(now + 7 * day)
        // A launch that waited for its report at the wrong time, and the app in front then.
        usage.recordLaunch(maps.key, at = now + 7 * day - 1_000)
        source.resumed += screen("com.mail", "com.mail.Inbox", now + 60_000)

        sync.sync(now)

        assertThat(usage.systemCount.first()).isEqualTo(SystemCount(period = 1, syncedTo = now, inFront = null))
        assertThat(usage.ownLaunches.first()).isEmpty()

        sync.sync(now + 120_000)

        assertThat(source.asked.last()).isEqualTo(now to now + 120_000)
        assertThat(scoreOf(mail, now + 120_000)).isWithin(0.05f).of(1f)
    }

    /** The very moment that is read already is not read again, and nothing starts anew for it. */
    @Test fun theSameMomentIsNotReadTwiceAndStartsNothingAnew() = runTest {
        switchOn()
        sync.sync(now)
        usage.recordLaunch(maps.key, at = now - 100)
        val asked = source.asked.size

        sync.sync(now)

        assertThat(source.asked).hasSize(asked)
        assertThat(usage.ownLaunches.first()).containsExactly(maps.key to now - 100)
    }

    /** Android did not answer (its service threw, the user is still locked after a restart). That is not
     *  "nothing came to the front": the time read up to stays, and the next read that is answered brings what
     *  came to the front meanwhile. */
    @Test fun aReadThatWasNotAnsweredIsMadeAgain() = runTest {
        switchOn()
        source.resumed += screen("com.maps", "com.maps.Main", now - 60_000)
        source.fails = true

        sync.sync(now)

        assertThat(usage.systemSyncedTo.first()).isEqualTo(start)
        assertThat(usage.usage.first()).isEmpty()

        source.fails = false
        sync.sync(now + 60_000)

        assertThat(source.asked.last()).isEqualTo(start to now + 60_000)
        assertThat(scoreOf(maps, now + 60_000)).isWithin(0.05f).of(1f)
        assertThat(usage.systemSyncedTo.first()).isEqualTo(now + 60_000)
    }

    /** The launcher's own apps could not be told (the listing failed, or has not come): no opening could be
     *  known for an app, so the span is not taken for read either. */
    @Test fun withoutTheAppsNothingCountsAsRead() = runTest {
        switchOn()
        source.resumed += screen("com.maps", "com.maps.Main", now - 60_000)
        val apps = known
        known = null

        sync.sync(now)

        assertThat(usage.systemSyncedTo.first()).isEqualTo(start)

        known = apps
        sync.sync(now + 60_000)

        assertThat(scoreOf(maps, now + 60_000)).isWithin(0.05f).of(1f)
    }

    /** An empty list of the launcher's own user's apps is a listing that failed, never the truth. */
    @Test fun theLaunchersOwnUsersAppsAreNeverNone() {
        val work = AppItem("com.maps", "com.maps.Main", android.os.UserHandle.getUserHandleForUid(10 * 100_000), 10L, "Maps")

        assertThat(appsOfUser(listOf(mail, work, maps), user)).containsExactly(mail, maps).inOrder()
        assertThat(appsOfUser(emptyList(), user)).isNull()
        assertThat(appsOfUser(listOf(work), user)).isNull()
    }

    // --- the app in front, from one read to the next ----------------------------------------------------------

    /** One read ended with mail's inbox in front, and the next begins with a message opened in it: the same
     *  app's next screen, with no other app between. It is the one opening it was in a single read. */
    @Test fun theAppInFrontWhenAReadEndsIsStillInFrontWhenTheNextBegins() = runTest {
        switchOn()
        source.resumed += screen("com.mail", "com.mail.Inbox", now - 1_000)
        sync.sync(now)
        assertThat(usage.systemCount.first()?.inFront).isEqualTo("com.mail")

        source.resumed += screen("com.mail", "com.mail.Thread", now + 1_000)
        sync.sync(now + 60_000)

        assertThat(scoreOf(mail, now + 60_000)).isWithin(0.05f).of(1f)
        assertThat(usage.usage.first().keys).containsExactly(mail.key)

        // A read with nothing in it leaves the app in front where it was; the launcher between makes the
        // app's next coming to the front an opening again.
        sync.sync(now + 120_000)
        source.resumed += listOf(
            screen("org.arkikeskus.launcher", "Home", now + 130_000),
            screen("com.mail", "com.mail.Inbox", now + 140_000),
        )
        sync.sync(now + 180_000)

        assertThat(scoreOf(mail, now + 180_000)).isWithin(0.05f).of(2f)
    }

    /** After a pause longer than what is read back, the stretch between was never read: what was in front
     *  before it says nothing of what is in front after it. */
    @Test fun theAppInFrontIsForgottenOverAStretchThatWasNotRead() = runTest {
        switchOn(at = now - 30 * day)
        source.resumed += screen("com.mail", "com.mail.Inbox", now - 20 * day - 1_000)
        sync.sync(now - 20 * day)

        source.resumed += screen("com.mail", "com.mail.Inbox", now - 1_000)
        sync.sync(now)

        assertThat(source.asked.last()).isEqualTo(now - 7 * day to now)
        assertThat(usage.usage.first().getValue(mail.key).lastUsed).isEqualTo(now - 1_000)
    }

    // --- which report is the launcher's own launch ------------------------------------------------------------

    /** Mail was opened from a notification, and five seconds later its other entry was started in the launcher.
     *  Android's report of the launch comes AFTER the launch: what came to the front before it was another
     *  opening, the notification's, and is counted for itself. */
    @Test fun whatCameToTheFrontBeforeALaunchIsNotThatLaunchsReport() = runTest {
        switchOn()
        source.resumed += listOf(
            screen("com.mail", "com.mail.Inbox", now - 60_000), // from the notification
            screen("org.arkikeskus.launcher", "Home", now - 57_000),
            screen("com.mail", "com.mail.Compose", now - 54_700), // the launch below, as Android saw it
        )
        usage.recordLaunch(mailCompose.key, at = now - 55_000)

        sync.sync(now)

        assertThat(scoreOf(mail)).isWithin(0.05f).of(1f)
        assertThat(scoreOf(mailCompose)).isWithin(0.05f).of(1f)
        assertThat(usage.ownLaunches.first()).isEmpty()
    }

    /** Of two comings to the front that could be a launch's report, the one that IS the entry the launcher
     *  started is it; the other one is counted for the entry it came up as. */
    @Test fun theScreenTheLauncherStartedIsItsReportBeforeAnotherOfTheSameApp() = runTest {
        switchOn()
        usage.recordLaunch(mailCompose.key, at = now - 60_000)
        source.resumed += listOf(
            screen("com.mail", "com.mail.Inbox", now - 59_000),
            screen("org.arkikeskus.launcher", "Home", now - 58_000),
            screen("com.mail", "com.mail.Compose", now - 57_000),
        )

        sync.sync(now)

        assertThat(scoreOf(mail)).isWithin(0.05f).of(1f)
        assertThat(scoreOf(mailCompose)).isWithin(0.05f).of(1f)
    }

    /**
     * The same icon tapped twice within ten seconds. The first start brought up a screen inside the app (the
     * app went on where it was), the user came home and tapped again, and the second start brought up the
     * entry itself. Each start has its own report: the first one's is the screen that came up before the app
     * was started again. Taken by its screen, the first start took the second one's report: the second was
     * left with none, the inner screen counted as an opening from elsewhere, and two launches made three.
     */
    @Test fun twoStartsOfOneIconInARowAreTwoLaunchesWhateverScreensCameUp() = runTest {
        switchOn()
        usage.recordLaunch(mail.key, at = now - 60_000)
        usage.recordLaunch(mail.key, at = now - 55_000)
        source.resumed += listOf(
            screen("com.mail", "com.mail.Thread", now - 59_700), // the first start: the app went on where it was
            screen("org.arkikeskus.launcher", "Home", now - 57_000),
            screen("com.mail", "com.mail.Inbox", now - 54_700), // the second start: the entry itself
        )

        sync.sync(now)

        assertThat(scoreOf(mail)).isWithin(0.05f).of(2f)
        assertThat(usage.usage.first().keys).containsExactly(mail.key)
        assertThat(usage.ownLaunches.first()).isEmpty()
    }

    /**
     * A launch's own note is written by a coroutine of its own, and a read of Android's record can get to the
     * store first (the user was back in the drawer at once). The launch is known from the moment it is asked
     * for, so the read still takes Android's report for the launcher's own; and the note, when it comes, does
     * not wait for a report that has been met. One launch, in either order of the two writes.
     */
    @Test fun aLaunchWhoseOwnNoteIsWrittenAfterTheReadIsStillOneLaunch() = runTest {
        switchOn()
        val launch = usage.launchAsked(maps.key, at = now - 3_000)
        source.resumed += screen("com.maps", "com.maps.Main", now - 2_700)

        sync.sync(now)
        assertThat(usage.usage.first()).isEmpty()
        usage.recordLaunch(launch)

        assertThat(scoreOf(maps)).isWithin(0.05f).of(1f)
        assertThat(usage.ownLaunches.first()).isEmpty()

        // The app opened from somewhere else a few seconds later is an opening of its own: the launch above
        // has had its report, and is not waiting to be taken for this one.
        source.resumed += listOf(
            screen("org.arkikeskus.launcher", "Home", now + 500),
            screen("com.maps", "com.maps.Route", now + 1_000),
        )
        sync.sync(now + 60_000)

        assertThat(scoreOf(maps, now + 60_000)).isWithin(0.05f).of(2f)
    }

    /** A launch asked for whose report is not in the read yet is simply written down when its note comes, and
     *  answered by the next read, as one whose note came first; and a start Android refused leaves nothing. */
    @Test fun aLaunchAskedForIsWrittenDownByItsOwnNoteAndARefusedStartLeavesNothing() = runTest {
        switchOn()
        val launch = usage.launchAsked(maps.key, at = now - 200)
        val refused = usage.launchAsked(mail.key, at = now - 100)
        usage.launchNotMade(refused)

        sync.sync(now)
        usage.recordLaunch(launch)
        assertThat(usage.ownLaunches.first()).containsExactly(maps.key to now - 200)

        source.resumed += listOf(
            screen("com.maps", "com.maps.Main", now + 100),
            screen("org.arkikeskus.launcher", "Home", now + 2_000),
            screen("com.mail", "com.mail.Inbox", now + 3_000),
        )
        sync.sync(now + 60_000)

        assertThat(scoreOf(maps, now + 60_000)).isWithin(0.05f).of(1f)
        // Mail was opened from elsewhere: the start that was refused answers for nothing.
        assertThat(scoreOf(mail, now + 60_000)).isWithin(0.05f).of(1f)
    }

    /** A work profile's app is started in the launcher too, and scored as it always was. Android tells nothing
     *  of it here, so its launch is not remembered for a report that cannot come: on a phone where the access
     *  is never given, such launches would push the personal ones out, and those would count twice later. */
    @Test fun aWorkAppsLaunchIsScoredButNotRememberedForAReport() = runTest {
        switchOn()
        val workMaps = "com.maps/com.maps.Main/10"

        usage.recordLaunch(workMaps, at = now - 60_000, androidReports = false)
        usage.recordLaunch(maps.key, at = now - 50_000)

        assertThat(usage.usage.first().keys).containsExactly(workMaps, maps.key)
        assertThat(usage.ownLaunches.first()).containsExactly(maps.key to now - 50_000)
    }

    // --- an opening read late ---------------------------------------------------------------------------------

    /** The app was started from the dock today, and the read then brings an opening of nearly a week ago (a
     *  notification's). With a half-life of a week that opening is worth about half a launch by now, not a
     *  whole one: the order of "most used" must not depend on when the drawer happened to be opened. */
    @Test fun anOpeningReadLateCountsForWhatItIsWorthByNow() = runTest {
        switchOn(at = now - 7 * day)
        source.resumed += listOf(
            screen("com.maps", "com.maps.Main", now - 7 * day + 3_600_000),
            screen("org.arkikeskus.launcher", "Home", now - 6 * day),
            screen("com.maps", "com.maps.Main", now - 59_700),
        )
        usage.recordLaunch(maps.key, at = now - 60_000)

        sync.sync(now)

        assertThat(scoreOf(maps)).isWithin(0.02f).of(1.5f)
        assertThat(usage.usage.first().getValue(maps.key).lastUsed).isEqualTo(now - 60_000)
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
