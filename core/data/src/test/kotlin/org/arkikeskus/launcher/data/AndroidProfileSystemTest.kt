package org.arkikeskus.launcher.data

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.content.Intent
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** `UserInfo.FLAG_MANAGED_PROFILE` (the class is not in the SDK stubs). */
private const val FLAG_MANAGED_PROFILE = 0x00000020

/** `UserHandle.of(10)` is not in the SDK stubs; a uid in user 10's range resolves to the same handle. */
private fun workHandle(): UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)

/**
 * The platform side of the Work tab on Android 14 (API 34), where the public API does not type
 * profiles: every profile the user manager lists is reported with its serial and quiet mode, and
 * each profile broadcast becomes one change event from one receiver that goes when the collector
 * does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidProfileSystemTest {
    @Test fun belowAndroid15ThereIsNoStoreAndNoSettingsPageForAPrivateSpace() {
        val system = AndroidProfileSystem(RuntimeEnvironment.getApplication())
        assertThat(system.privateInstallSender(10)).isNull()
        assertThat(system.privateSettingsTarget()).isNull()
    }

    @Config(sdk = [35])
    @Test fun onAndroid15TheSettingsButtonLeadsToTheSecurityPageWhenThereIsOne() {
        val app: Application = RuntimeEnvironment.getApplication()
        val system = AndroidProfileSystem(app)
        // Nothing answers the security settings intent on this device: no button.
        assertThat(system.privateSettingsTarget()).isNull()

        val page = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "SecurityDashboard"
                applicationInfo = ApplicationInfo().apply { packageName = "com.android.settings" }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_SECURITY_SETTINGS), page)

        val target = system.privateSettingsTarget() as PrivateSettingsTarget.Plain
        assertThat(target.intent.action).isEqualTo(Settings.ACTION_SECURITY_SETTINGS)
        assertThat(target.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test fun androidsOwnHideSettingCannotBeReadBelowAndroid16() {
        assertThat(AndroidProfileSystem(RuntimeEnvironment.getApplication()).privateSpaceEntryHidden(10)).isNull()
    }

    private val context: Application = RuntimeEnvironment.getApplication()
    private val userManager = shadowOf(context.getSystemService(UserManager::class.java))
    private val system = AndroidProfileSystem(context)
    private val work = workHandle()

    private fun addWorkProfile() {
        userManager.addUser(0, "Owner", 0)
        userManager.addProfile(0, 10, "Work", FLAG_MANAGED_PROFILE)
    }

    @Test fun snapshotListsTheOwnUserAsOwnAndAProfileWithItsSerial() {
        addWorkProfile()

        val snapshot = system.snapshot()

        assertThat(snapshot.map { it.serial to it.kind })
            .containsExactly(0L to ProfileKind.Own, 10L to ProfileKind.Unknown)
    }

    @Test fun snapshotReadsQuietModeFromTheSystem() {
        addWorkProfile()
        assertThat(system.snapshot().single { it.serial == 10L }.paused).isFalse()

        context.getSystemService(UserManager::class.java).requestQuietModeEnabled(true, work)

        assertThat(system.snapshot().single { it.serial == 10L }.paused).isTrue()
    }

    @Test fun changesEmitsARefreshOnceTheReceiverIsRegistered() = runTest {
        // A profile that changes while the launcher starts — after the repository's first read but
        // before this receiver exists — sends a broadcast nobody hears. The registration itself is
        // therefore one change event, so the state is re-read once the receiver is listening.
        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { system.changes().collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(profileReceivers()).hasSize(1)
        assertThat(seen).hasSize(1)
    }

    @Test fun changesEmitsOncePerProfileBroadcast() = runTest {
        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { system.changes().collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        val afterRegistration = seen.size

        context.sendBroadcast(Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE))
        context.sendBroadcast(Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE))
        context.sendBroadcast(Intent("android.intent.action.PROFILE_ADDED"))
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(seen.size - afterRegistration).isEqualTo(3)
    }

    @Test fun theReceiverGoesWhenTheCollectorStops() = runTest {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { system.changes().collect {} }
        assertThat(profileReceivers()).hasSize(1)

        job.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(profileReceivers()).isEmpty()
    }

    @Test fun requestQuietModeDelegatesToUserManager() {
        addWorkProfile()

        assertThat(system.requestQuietMode(10, true)).isTrue()

        assertThat(context.getSystemService(UserManager::class.java).isQuietModeEnabled(work)).isTrue()
    }

    @Test fun theBadgeProbeHasAPositiveIntrinsicSize() {
        // ApplicationPackageManager.getUserBadgedIcon draws the badge onto a bitmap of the probe's
        // intrinsic size: a -1 x -1 probe makes createBitmap throw, and every profile then looks
        // unbadged on API 30-34 (Launcher3's probe is a 1 x 1 ColorDrawable for this reason).
        val probe = BadgeProbeDrawable()

        assertThat(probe.intrinsicWidth).isEqualTo(1)
        assertThat(probe.intrinsicHeight).isEqualTo(1)
    }

    @Test fun theLateProfileActionsAreNotRegisteredBelowApi35() = runTest {
        // PROFILE_AVAILABLE / UNAVAILABLE are protected broadcasts only from API 35: on 34 an
        // exported receiver for them would let any app trigger profile re-reads and app rescans.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { system.changes().collect {} }

        val filter = profileReceivers().single().intentFilter
        assertThat(filter.hasAction(Intent.ACTION_PROFILE_ADDED)).isTrue()
        assertThat(filter.hasAction("android.intent.action.PROFILE_AVAILABLE")).isFalse()
        assertThat(filter.hasAction("android.intent.action.PROFILE_UNAVAILABLE")).isFalse()
    }

    private fun profileReceivers() = shadowOf(context).registeredReceivers
        .filter { it.intentFilter.hasAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE) }
}

/** On Android 15 the platform types profiles; without a shadow for that call it must still answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidProfileSystemApi35Test {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test fun snapshotNeverThrows() {
        val userManager = shadowOf(context.getSystemService(UserManager::class.java))
        userManager.addUser(0, "Owner", 0)
        userManager.addProfile(0, 10, "Work", FLAG_MANAGED_PROFILE)

        val snapshot = AndroidProfileSystem(context).snapshot()

        assertThat(snapshot.map { it.serial }).containsExactly(0L, 10L)
    }

    /** Android 15 types a managed profile itself. One it does not type (a private space the launcher may not see)
     *  must not pass for a work profile because it wears a badge: it would show as a paused Work tab. */
    @Test fun anUntypedProfileIsNeverBadgedOnAndroid15SoItIsNotAWorkProfile() {
        val userManager = shadowOf(context.getSystemService(UserManager::class.java))
        userManager.addUser(0, "Owner", 0)
        userManager.addProfile(0, 10, "Private", FLAG_MANAGED_PROFILE)

        val snapshot = AndroidProfileSystem(context).snapshot()

        val profile = snapshot.single { it.serial == 10L }
        assertThat(profile.kind).isEqualTo(ProfileKind.Unknown) // no shadow types it
        assertThat(profile.badged).isFalse()
        assertThat(workProfileOf(snapshot)).isNull()
    }

    @Test fun aBadgeCountsOnlyWhereThePlatformDoesNotTypeProfiles() {
        assertThat(badgeCounts(ProfileKind.Unknown, sdkInt = 34)).isTrue()
        assertThat(badgeCounts(ProfileKind.Unknown, sdkInt = 30)).isTrue()
        assertThat(badgeCounts(ProfileKind.Unknown, sdkInt = 35)).isFalse()
        assertThat(badgeCounts(ProfileKind.Unknown, sdkInt = 36)).isFalse()
        for (kind in ProfileKind.entries - ProfileKind.Unknown) assertThat(badgeCounts(kind, sdkInt = 34)).isFalse()
    }
}
