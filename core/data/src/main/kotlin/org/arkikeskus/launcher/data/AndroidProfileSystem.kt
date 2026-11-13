package org.arkikeskus.launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ProfileSystem] on the platform: `UserManager` for the profiles and their quiet mode,
 * `LauncherApps.getLauncherUserInfo` (API 35+) for the profile type, Launcher3's badge check below
 * that, and one `BroadcastReceiver` per collector of [changes] for the profile broadcasts —
 * `LauncherApps.Callback` never reports a profile coming, going or pausing.
 */
/**
 * Whether a profile's badge may tell what it is: only for an untyped one, and only where the platform does not type
 * profiles itself (below Android 15). From Android 15 a managed profile comes typed, so an untyped profile there
 * is one the launcher may not see (a private space without the access to it): its badge must not make it a work
 * profile, or it would show as a paused Work tab.
 */
internal fun badgeCounts(kind: ProfileKind, sdkInt: Int): Boolean =
    kind == ProfileKind.Unknown && sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM

@Singleton
class AndroidProfileSystem @Inject constructor(@ApplicationContext private val context: Context) : ProfileSystem {
    private val userManager = context.getSystemService(UserManager::class.java)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    override fun snapshot(): List<ProfileSnapshot> = runCatching {
        val own = Process.myUserHandle()
        val profiles = userManager?.userProfiles ?: listOf(own)
        profiles.mapNotNull { user ->
            val serial = userManager?.getSerialNumberForUser(user) ?: return@mapNotNull null
            if (serial < 0) return@mapNotNull null
            val kind = when {
                user == own -> ProfileKind.Own
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> platformKind(user)
                else -> ProfileKind.Unknown
            }
            ProfileSnapshot(
                serial = serial,
                kind = kind,
                badged = badgeCounts(kind, Build.VERSION.SDK_INT) && isBadged(user),
                paused = runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false),
            )
        }
    }.getOrDefault(emptyList())

    /** The platform's own typing of [user]; [ProfileKind.Unknown] when it does not describe it. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun platformKind(user: UserHandle): ProfileKind =
        when (runCatching { launcherApps?.getLauncherUserInfo(user) }.getOrNull()?.userType) {
            null -> ProfileKind.Unknown
            UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.Managed
            UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileKind.Private
            UserManager.USER_TYPE_PROFILE_CLONE -> ProfileKind.Clone
            else -> ProfileKind.Unknown
        }

    /** Launcher3 `ApiWrapper.queryAllUsers`: a badged profile comes back as a different drawable. */
    private fun isBadged(user: UserHandle): Boolean = runCatching {
        val probe = BadgeProbeDrawable()
        context.packageManager.getUserBadgedIcon(probe, user) !== probe
    }.getOrDefault(false)

    override fun changes(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
                addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
                addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                // Sent and protected from API 35 on; as string literals, the way Launcher3's
                // UserCache has them. Not registered below 35: there the names are unprotected,
                // and this receiver is exported.
                addAction("android.intent.action.PROFILE_AVAILABLE")
                addAction("android.intent.action.PROFILE_UNAVAILABLE")
            }
        }
        // EXPORTED on purpose, as in SystemFlagsMonitor: every registered action is a protected system
        // broadcast (only the platform can send them, and onReceive only re-reads live state). The
        // NOT_EXPORTED backport guards pre-T receivers with a signature permission that only
        // system-uid senders bypass, which has silently dropped other system broadcasts there.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // Now listening: ask for one re-read, since a change between the caller's first read and
        // this registration (the launcher starting while a profile is set up) was not heard.
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    override fun requestQuietMode(serial: Long, enabled: Boolean): Boolean {
        val um = userManager ?: throw IllegalStateException("no UserManager")
        val user = um.getUserForSerialNumber(serial) ?: throw IllegalStateException("profile $serial is gone")
        return um.requestQuietModeEnabled(enabled, user)
    }

    override fun privateSpaceEntryHidden(serial: Long): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        return runCatching { entryHidden(serial) }.getOrNull()
    }

    override fun privateInstallSender(serial: Long): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return runCatching { marketSender(serial) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun marketSender(serial: Long): IntentSender? {
        val user = userManager?.getUserForSerialNumber(serial) ?: return null
        return launcherApps?.getAppMarketActivityIntent(null, user)
    }

    override fun privateSettingsTarget(): PrivateSettingsTarget? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA ->
            runCatching { settingsSender() }.getOrNull()?.let { PrivateSettingsTarget.Sender(it) }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
            // Android 15 has no private space page a launcher may open: the Security page is one tap from it.
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolves = runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull() != null
            if (resolves) PrivateSettingsTarget.Plain(intent) else null
        }
        else -> null
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun settingsSender(): IntentSender? = launcherApps?.privateSpaceSettingsIntent

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun entryHidden(serial: Long): Boolean? {
        val user = userManager?.getUserForSerialNumber(serial) ?: return null
        val config = launcherApps?.getLauncherUserInfo(user)?.userConfig ?: return null
        val key = LauncherUserInfo.PRIVATE_SPACE_ENTRYPOINT_HIDDEN
        return if (config.containsKey(key)) config.getBoolean(key) else null
    }
}

/**
 * The probe behind the badge check: only its identity matters, but `getUserBadgedIcon` draws the
 * badge onto a bitmap of the probe's intrinsic size, so it must be a positive 1 x 1 (Launcher3's
 * `ApiWrapper.NoopDrawable`) — a sizeless drawable makes the platform throw and every profile
 * look unbadged.
 */
internal class BadgeProbeDrawable : ColorDrawable() {
    override fun getIntrinsicWidth(): Int = 1
    override fun getIntrinsicHeight(): Int = 1
}
