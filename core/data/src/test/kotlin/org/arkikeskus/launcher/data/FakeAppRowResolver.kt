package org.arkikeskus.launcher.data

/** Scriptable [AppRowResolver]; by default every package is [PackageTargets.Unknown] (rows kept). */
class FakeAppRowResolver : AppRowResolver {
    /** package → its launcher activities. */
    val targets = HashMap<String, PackageTargets>()

    /** package → shortcut ids the system no longer has. */
    val missing = HashMap<String, Set<String>>()

    /** Packages that are only hidden for now (paused profile, unmounted storage). */
    val unavailable = HashSet<String>()

    override fun launchTargets(packageName: String, userSerial: Long): PackageTargets =
        targets[packageName] ?: PackageTargets.Unknown

    override fun missingShortcuts(packageName: String, userSerial: Long, shortcutIds: Collection<String>): Set<String> =
        missing[packageName].orEmpty().intersect(shortcutIds.toSet())

    override fun isTemporarilyUnavailable(packageName: String, userSerial: Long): Boolean = packageName in unavailable

    var ownSerial: Long? = 0L

    override fun ownUserSerial(): Long? = ownSerial
}
