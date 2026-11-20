package org.arkikeskus.launcher.data

import android.os.Process
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
 * access, never twice for the same moment, and never for anything the launcher does not show.
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
    private val usage = AppUsageRepository(CountingStore())
    private var switchedOn = true
    private val sync = SystemUsageSync(
        source = source,
        usage = usage,
        enabled = { switchedOn },
        apps = { listOf(mail, mailCompose, maps) },
        ownPackage = "org.arkikeskus.launcher",
    )

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
        source.resumed += screen("com.maps", "com.maps.Main", now - 3_600_000)
        sync.sync(now)

        source.resumed += screen("com.mail", "com.mail.Inbox", now + 60_000)
        sync.sync(now + 120_000)

        // The second sync asked only for what came after the first.
        assertThat(source.asked.last()).isEqualTo(now to now + 120_000)
        assertThat(scoreOf(maps, now + 120_000)).isWithin(0.05f).of(1f)
        assertThat(scoreOf(mail, now + 120_000)).isWithin(0.05f).of(1f)
    }

    /** The first sync, and one after a long pause, reads a week at most: what is older has all but faded from
     *  the score anyway, and Android keeps these events for days, not for ever. */
    @Test fun atMostAWeekIsRead() = runTest {
        sync.sync(now)

        assertThat(source.asked.single()).isEqualTo(now - 7 * day to now)
    }

    @Test fun withTheSwitchOffOrWithoutAccessNothingIsReadAndNothingCounted() = runTest {
        source.resumed += screen("com.maps", "com.maps.Main", now - 1_000)

        switchedOn = false
        sync.sync(now)
        switchedOn = true
        source.access = false
        sync.sync(now)

        assertThat(source.asked).isEmpty()
        assertThat(usage.usage.first()).isEmpty()
        // Nothing was read, so nothing counts as read: turned on later, the last week is still there to count.
        assertThat(usage.systemSyncedTo.first()).isEqualTo(0L)
    }

    /** A launch made in the launcher is recorded by the launcher at once, and Android reports the same launch a
     *  moment later: it is one launch. An opening long before the stored one still counts. */
    @Test fun aLaunchTheLauncherRecordedItselfIsNotCountedAgain() = runTest {
        usage.recordOpenings(listOf(maps.key to now - 60_000), syncedTo = 0)
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
}
