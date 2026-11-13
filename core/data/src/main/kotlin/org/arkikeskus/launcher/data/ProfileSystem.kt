package org.arkikeskus.launcher.data

import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.flow.Flow

/** How the platform types a user profile, as far as the public API tells a launcher. */
enum class ProfileKind {
    /** The launcher's own user. */
    Own,
    /** A managed (work) profile — API 35+ `UserManager.USER_TYPE_PROFILE_MANAGED`. */
    Managed,
    /** Android 15 Private Space — API 35+ `USER_TYPE_PROFILE_PRIVATE`. */
    Private,
    /** An app-cloning profile — API 35+ `USER_TYPE_PROFILE_CLONE`. */
    Clone,
    /** Not typed by the platform: API 30–34, or a profile `LauncherApps.getLauncherUserInfo` does not
     *  describe. The badge check decides then (see [workProfileOf]). */
    Unknown,
}

/**
 * One profile as the platform reports it right now. [badged] is Launcher3's `ApiWrapper.queryAllUsers`
 * check — `PackageManager.getUserBadgedIcon` returns a different drawable for a badged profile — and
 * is what tells a work profile from the own user where [kind] is [ProfileKind.Unknown]. [paused] is
 * the profile's quiet mode.
 */
data class ProfileSnapshot(val serial: Long, val kind: ProfileKind, val badged: Boolean, val paused: Boolean)

/**
 * The platform's profile knowledge behind the drawer's Work tab, as an interface so the decision and
 * the repository are testable with a fake. The launcher is the device HOME, so [snapshot] never
 * throws: a lookup that fails answers an empty list.
 */
interface ProfileSystem {
    /** Every profile the launcher can see, the own user included; empty when the lookup fails. */
    fun snapshot(): List<ProfileSnapshot>

    /** One `Unit` right after the receiver is registered — a request to re-read, because a profile
     *  that changed before the receiver existed sent a broadcast nobody heard — then one per profile
     *  broadcast (added / removed / available / unavailable / unlocked). Cold: each collector
     *  registers its own receiver, so share it. */
    fun changes(): Flow<Unit>

    /** `UserManager.requestQuietModeEnabled` for the profile with [serial]; throws what the platform
     *  throws (a `SecurityException` when the app is not the default launcher). */
    fun requestQuietMode(serial: Long, enabled: Boolean): Boolean

    /** Android's own "hide private space when locked" for the profile with [serial]; null where it cannot be
     *  read (below Android 16) or the lookup fails. Never throws. */
    fun privateSpaceEntryHidden(serial: Long): Boolean?

    /** What opens an app store inside the private profile with [serial] (`LauncherApps.getAppMarketActivityIntent`,
     *  Android 15); null when there is none to open right now. Never throws. */
    fun privateInstallSender(serial: Long): IntentSender?

    /** What opens the private space's settings: Android's own page on Android 16 and later, its Security page
     *  on Android 15 (Private space is one tap away there); null when neither can be opened. Never throws. */
    fun privateSettingsTarget(): PrivateSettingsTarget?
}

/** Where the Private tab's settings button leads: the two kinds are started differently. */
sealed interface PrivateSettingsTarget {
    /** The system's own sender for the private space's settings (Android 16 and later). */
    data class Sender(val sender: IntentSender) : PrivateSettingsTarget

    /** A plain settings page, started like any activity. */
    data class Plain(val intent: Intent) : PrivateSettingsTarget
}

/**
 * The one profile the Work tab shows, or null: a [ProfileKind.Managed] profile, or an
 * [ProfileKind.Unknown] one that is badged (API 30–34 cannot tell a clone profile apart, and
 * Launcher3 lumps them together there too). The own user, private and clone profiles never
 * qualify. Exactly one work profile is supported: the lowest serial wins.
 */
fun workProfileOf(profiles: List<ProfileSnapshot>): ProfileSnapshot? = profiles
    .filter { it.kind == ProfileKind.Managed || (it.kind == ProfileKind.Unknown && it.badged) }
    .minByOrNull { it.serial }

/**
 * The private space among [profiles], or null: only a profile the platform typed [ProfileKind.Private] (so
 * never below Android 15, and never by a badge). Android allows one; the lowest serial wins.
 */
fun privateProfileOf(profiles: List<ProfileSnapshot>): ProfileSnapshot? = profiles
    .filter { it.kind == ProfileKind.Private }
    .minByOrNull { it.serial }
