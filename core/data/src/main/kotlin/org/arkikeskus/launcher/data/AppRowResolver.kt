package org.arkikeskus.launcher.data

/** What the system says about one (package, profile) when the home layout is repaired. */
sealed interface PackageTargets {
    /** No definitive answer: a paused or locked profile, a disabled or missing package, a failed
     *  query. Rows of such a package are left exactly as they are. */
    data object Unknown : PackageTargets

    /** Installed and enabled in an available profile; [classNames] are its launcher activities now. */
    data class Launchable(val classNames: List<String>) : PackageTargets
}

/**
 * The platform answers the home-layout repairs need (implemented by [LauncherAppsSource]). Every
 * answer is fail-safe: when the system can't say for sure, the reply is the one that keeps rows.
 */
interface AppRowResolver {
    /** See [PackageTargets]. */
    fun launchTargets(packageName: String, userSerial: Long): PackageTargets

    /** The [shortcutIds] the system definitively no longer has for (package, profile). Empty
     *  whenever the query can't be answered: not the default home app, a paused or locked profile,
     *  a disabled package, an error. */
    fun missingShortcuts(packageName: String, userSerial: Long, shortcutIds: Collection<String>): Set<String>

    /** True while an app is only hidden for now and will come back by itself: its profile is paused
     *  or locked, or the package lives on storage that is currently unmounted. */
    fun isTemporarilyUnavailable(packageName: String, userSerial: Long): Boolean
}
