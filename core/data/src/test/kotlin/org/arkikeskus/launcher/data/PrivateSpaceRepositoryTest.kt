package org.arkikeskus.launcher.data

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The private space as the drawer will see it: whether the device has one, whether it is locked, whether its
 * entry is hidden while it is locked, and its apps. The apps are there only while the space is unlocked: the
 * moment the state says locked the list is empty, whatever the app scan still holds. Locking and unlocking go
 * to the system, with a transition window until it reports the change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrivateSpaceRepositoryTest {
    private val own = ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)
    private val work = ProfileSnapshot(10, ProfileKind.Managed, badged = false, paused = false)
    private fun space(locked: Boolean) = ProfileSnapshot(11, ProfileKind.Private, badged = false, paused = locked)

    private val user: UserHandle = UserHandle.getUserHandleForUid(11 * 100_000)
    private val bank = AppItem("com.bank", "com.bank.Main", user, 11, "Bank")
    private val notes = AppItem("com.notes", "com.notes.Main", user, 11, "Notes")

    /** What the app scan last listed for the private profile. */
    private val scanned = MutableStateFlow<List<AppItem>>(emptyList())
    private val hidePreference = MutableStateFlow<Boolean?>(false)
    private val started = mutableListOf<AppItem>()

    private fun TestScope.repository(fake: FakeProfileSystem): PrivateSpaceRepository {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return PrivateSpaceRepository(
            system = fake,
            changes = fake.changes(),
            scannedApps = scanned,
            start = { app, _, _ -> started += app; Result.success(Unit) },
            hidePreference = hidePreference,
            scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher),
            io = dispatcher,
            transitionTimeoutMs = 5_000,
        )
    }

    @Test fun aDeviceWithoutAPrivateSpaceHasNoStateAndNoApps() = runTest {
        scanned.value = listOf(bank)
        val repo = repository(FakeProfileSystem(listOf(own, work)))

        assertThat(repo.state.value).isNull()
        assertThat(repo.apps.value).isEmpty()
        assertThat(repo.isPrivate(11)).isFalse()
    }

    @Test fun theStateIsReadInTheConstructorNotOnTheScope() = runTest {
        // In the app the scope is Dispatchers.Default: a state that only arrives once the scope runs would let
        // the first reader take a private profile for none.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = PrivateSpaceRepository(
            system = FakeProfileSystem(listOf(own, space(locked = true))),
            changes = MutableStateFlow(Unit),
            scannedApps = scanned,
            start = { _, _, _ -> Result.success(Unit) },
            hidePreference = hidePreference,
            scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher),
            io = dispatcher,
            transitionTimeoutMs = 5_000,
        )

        assertThat(repo.state.value).isEqualTo(PrivateSpace(userSerial = 11, locked = true, hiddenWhenLocked = false))
        assertThat(repo.isPrivate(11)).isTrue()
    }

    @Test fun aLockedSpaceHasNoAppsWhateverTheScanStillHolds() = runTest {
        scanned.value = listOf(bank, notes)
        val repo = repository(FakeProfileSystem(listOf(own, space(locked = true))))

        assertThat(repo.state.value).isEqualTo(PrivateSpace(userSerial = 11, locked = true, hiddenWhenLocked = false))
        assertThat(repo.apps.value).isEmpty()
    }

    @Test fun anUnlockedSpaceShowsWhatTheScanListed() = runTest {
        val repo = repository(FakeProfileSystem(listOf(own, space(locked = false))))
        assertThat(repo.state.value).isEqualTo(PrivateSpace(userSerial = 11, locked = false, hiddenWhenLocked = false))
        assertThat(repo.apps.value).isEmpty()

        scanned.value = listOf(bank)
        assertThat(repo.apps.value).containsExactly(bank)
        scanned.value = listOf(bank, notes)
        assertThat(repo.apps.value).containsExactly(bank, notes).inOrder()
    }

    @Test fun lockingEmptiesTheAppsBeforeAnyoneCanSeeTheStateLocked() = runTest {
        val fake = FakeProfileSystem(listOf(own, space(locked = false)))
        val repo = repository(fake)
        scanned.value = listOf(bank, notes)
        assertThat(repo.apps.value).hasSize(2)
        // Whoever sees the state locked must find the list empty at that very moment.
        val appsSeenWhenLocked = mutableListOf<List<AppItem>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.state.collect { if (it?.locked == true) appsSeenWhenLocked += repo.apps.value }
        }

        // The space locks; the scan that listed its apps has not run again yet.
        fake.profiles = listOf(own, space(locked = true))
        fake.broadcasts.emit(Unit)

        assertThat(repo.state.value?.locked).isTrue()
        assertThat(repo.apps.value).isEmpty()
        assertThat(appsSeenWhenLocked).containsExactly(emptyList<AppItem>())
    }

    @Test fun aSpaceThatIsRemovedLeavesNothing() = runTest {
        val fake = FakeProfileSystem(listOf(own, space(locked = false)))
        val repo = repository(fake)
        scanned.value = listOf(bank)

        fake.profiles = listOf(own)
        fake.broadcasts.emit(Unit)

        assertThat(repo.state.value).isNull()
        assertThat(repo.apps.value).isEmpty()
        assertThat(repo.isPrivate(11)).isFalse()
    }

    @Test fun unlockingAsksTheSystemAndIsInTransitionUntilItReportsTheChange() = runTest {
        val fake = FakeProfileSystem(listOf(own, space(locked = true)))
        val asked = mutableListOf<Pair<Long, Boolean>>()
        fake.request = { serial, enabled -> asked += serial to enabled; false } // false: the system shows its own prompt
        val repo = repository(fake)

        assertThat(repo.setLocked(false)).isEqualTo(PauseOutcome.Requested)

        assertThat(asked).containsExactly(11L to false)
        assertThat(repo.state.value).isEqualTo(PrivateSpace(11, locked = true, hiddenWhenLocked = false, transitioning = true))

        fake.profiles = listOf(own, space(locked = false))
        fake.broadcasts.emit(Unit)
        assertThat(repo.state.value).isEqualTo(PrivateSpace(11, locked = false, hiddenWhenLocked = false, transitioning = false))
    }

    @Test fun aTransitionTheSystemNeverAnswersEndsByItself() = runTest {
        val fake = FakeProfileSystem(listOf(own, space(locked = true)))
        val repo = repository(fake)

        repo.setLocked(false)
        advanceTimeBy(4_999)
        assertThat(repo.state.value?.transitioning).isTrue()
        advanceTimeBy(2) // the user cancelled the system's prompt: nothing was ever reported
        assertThat(repo.state.value).isEqualTo(PrivateSpace(11, locked = true, hiddenWhenLocked = false, transitioning = false))
    }

    @Test fun lockingAsksTheSystemToo() = runTest {
        val fake = FakeProfileSystem(listOf(own, space(locked = false)))
        val asked = mutableListOf<Pair<Long, Boolean>>()
        fake.request = { serial, enabled -> asked += serial to enabled; true }
        val repo = repository(fake)

        assertThat(repo.setLocked(true)).isEqualTo(PauseOutcome.Requested)
        assertThat(asked).containsExactly(11L to true)
    }

    @Test fun onlyTheDefaultLauncherMayLockOrUnlock() = runTest {
        val fake = FakeProfileSystem(listOf(own, space(locked = true)))
        fake.request = { _, _ -> throw SecurityException("not the default launcher") }
        val repo = repository(fake)

        assertThat(repo.setLocked(false)).isEqualTo(PauseOutcome.NotDefaultLauncher)
        assertThat(repo.state.value?.transitioning).isFalse()
    }

    @Test fun aRequestThePlatformRejectsAndOneWithoutASpaceFail() = runTest {
        val fake = FakeProfileSystem(listOf(own, space(locked = true)))
        fake.request = { _, _ -> throw IllegalStateException("profile 11 is gone") }
        assertThat(repository(fake).setLocked(false)).isEqualTo(PauseOutcome.Failed)

        assertThat(repository(FakeProfileSystem(listOf(own))).setLocked(false)).isEqualTo(PauseOutcome.Failed)
    }

    @Test fun hidingWhenLockedFollowsThePreferenceWhereTheSystemHasNoAnswer() = runTest {
        val repo = repository(FakeProfileSystem(listOf(own, space(locked = true)))) // entryHidden answers null: Android 15
        assertThat(repo.state.value?.hiddenWhenLocked).isFalse()

        hidePreference.value = true
        assertThat(repo.state.value?.hiddenWhenLocked).isTrue()
    }

    @Test fun untilTheSwitchIsReadTheEntryCountsAsHidden() = runTest {
        // The stored settings arrive a moment after the process starts: showing the entry meanwhile would show
        // it to someone who had it hidden.
        hidePreference.value = null
        val repo = repository(FakeProfileSystem(listOf(own, space(locked = true))))
        assertThat(repo.state.value?.hiddenWhenLocked).isTrue()

        hidePreference.value = false
        assertThat(repo.state.value?.hiddenWhenLocked).isFalse()
    }

    @Test fun hidingWhenLockedFollowsTheSystemWhereItAnswers() = runTest {
        val fake = FakeProfileSystem(listOf(own, space(locked = true)))
        val asked = mutableListOf<Long>()
        fake.entryHidden = { serial -> asked += serial; true }
        val repo = repository(fake)
        assertThat(repo.state.value?.hiddenWhenLocked).isTrue() // the launcher's own switch is off
        assertThat(asked).contains(11L)

        fake.entryHidden = { false }
        hidePreference.value = true
        fake.broadcasts.emit(Unit)
        assertThat(repo.state.value?.hiddenWhenLocked).isFalse() // and on: the system's answer wins both ways
    }

    @Test fun refreshReadsTheSystemsHideFlagAgain() = runTest {
        val fake = FakeProfileSystem(listOf(own, space(locked = true)))
        fake.entryHidden = { false }
        val repo = repository(fake)
        assertThat(repo.state.value?.hiddenWhenLocked).isFalse()

        // Changed in Android's settings: no broadcast tells the launcher.
        fake.entryHidden = { true }
        assertThat(repo.state.value?.hiddenWhenLocked).isFalse()
        repo.refresh()

        assertThat(repo.state.value?.hiddenWhenLocked).isTrue()
    }

    @Test fun theInstallAndSettingsTargetsAreTheSystemsAndFollowItsChanges() = runTest {
        val context: Application = RuntimeEnvironment.getApplication()
        val store = PendingIntent.getActivity(context, 0, Intent("test.store"), PendingIntent.FLAG_IMMUTABLE).intentSender
        val settings = PrivateSettingsTarget.Plain(Intent("test.settings"))
        val fake = FakeProfileSystem(listOf(own, space(locked = true)))
        val repo = repository(fake)
        // A locked space has no store to open, and this Android no settings page: the buttons are not shown.
        assertThat(repo.installSender()).isNull()
        assertThat(repo.settingsTarget()).isNull()

        val asked = mutableListOf<Long>()
        fake.installSender = { serial -> asked += serial; store }
        fake.settingsTarget = settings
        fake.profiles = listOf(own, space(locked = false))
        fake.broadcasts.emit(Unit)

        assertThat(repo.installSender()).isSameInstanceAs(store)
        assertThat(repo.settingsTarget()).isEqualTo(settings)
        assertThat(asked).contains(11L)
    }

    @Test fun withoutAPrivateSpaceTheSystemIsNotAskedForItsTargets() = runTest {
        val fake = FakeProfileSystem(listOf(own, work))
        fake.installSender = { error("no private space to install into") }
        fake.settingsTarget = PrivateSettingsTarget.Plain(Intent("test.settings"))

        val repo = repository(fake)

        assertThat(repo.installSender()).isNull()
        assertThat(repo.settingsTarget()).isNull()
    }

    @Test fun onlyThePrivateProfilesSerialIsPrivate() = runTest {
        val repo = repository(FakeProfileSystem(listOf(own, work, space(locked = true))))

        assertThat(repo.isPrivate(11)).isTrue()
        assertThat(repo.isPrivate(0)).isFalse()
        assertThat(repo.isPrivate(10)).isFalse()
    }

    @Test fun fromAndroid15OnlyAProfileOfKnownSharedKindMayLeaveATrace() = runTest {
        val context: Application = RuntimeEnvironment.getApplication()
        val users = context.getSystemService(UserManager::class.java)
        shadowOf(users).addUser(0, "Owner", 0)
        for (id in listOf(10, 11, 12, 13, 14)) shadowOf(users).addProfile(0, id, "Profile $id", 0)
        fun user(id: Int): UserHandle = UserHandle.getUserHandleForUid(id * 100_000)
        val repo = repository(
            FakeProfileSystem(
                listOf(
                    own, work, space(locked = false),
                    ProfileSnapshot(12, ProfileKind.Clone, badged = false, paused = false),
                    ProfileSnapshot(13, ProfileKind.Unknown, badged = true, paused = false),
                ),
            ),
        )

        assertThat(repo.mustLeaveNoTrace(Process.myUserHandle(), users)).isFalse()
        assertThat(repo.mustLeaveNoTrace(user(10), users)).isFalse() // a work profile
        assertThat(repo.mustLeaveNoTrace(user(12), users)).isFalse() // a clone
        assertThat(repo.mustLeaveNoTrace(user(11), users)).isTrue() // the private space
        assertThat(repo.mustLeaveNoTrace(user(13), users)).isTrue() // of unknown kind, whatever its badge
        assertThat(repo.mustLeaveNoTrace(user(14), users)).isTrue() // not in the snapshot: created since
        assertThat(repo.mustLeaveNoTrace(user(15), users)).isTrue() // no serial to be had
        assertThat(repo.mustLeaveNoTrace(user(11), userManager = null)).isTrue()
        // Below Android 15 there is no private space: nothing is kept out.
        for (id in listOf(10, 11, 13, 14, 15)) assertThat(repo.mustLeaveNoTrace(user(id), users, sdkInt = 34)).isFalse()
    }

    @Test fun aPrivateAppIsStartedThroughTheSource() = runTest {
        val repo = repository(FakeProfileSystem(listOf(own, space(locked = false))))

        assertThat(repo.launch(bank).isSuccess).isTrue()

        assertThat(started).containsExactly(bank)
    }

    @Test fun thePrivateProfileIsTheOneTypedPrivateWithTheLowestSerial() {
        assertThat(privateProfileOf(listOf(own, work))).isNull()
        assertThat(privateProfileOf(listOf(own, work, space(locked = true)))).isEqualTo(space(locked = true))
        val later = ProfileSnapshot(12, ProfileKind.Private, badged = false, paused = false)
        assertThat(privateProfileOf(listOf(later, own, space(locked = true)))).isEqualTo(space(locked = true))
        // A badge or an unknown kind never makes a private space.
        assertThat(privateProfileOf(listOf(own, ProfileSnapshot(13, ProfileKind.Unknown, badged = true, paused = true)))).isNull()
    }
}
