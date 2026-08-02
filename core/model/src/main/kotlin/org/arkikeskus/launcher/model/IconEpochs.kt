package org.arkikeskus.launcher.model

/**
 * Monotonic re-fetch tokens for app icons, fed into [IconRequest.epoch] (and so the Coil cache key).
 * Bumping a package (its APK was updated/replaced) or the global generation (the active icon pack's
 * content changed) changes the key for the affected icons, which both re-launches any on-screen
 * AsyncImage showing them AND misses the memory cache — so the fresh icon appears immediately.
 * Without this, an already-composed icon kept its stale painter until the launcher process died
 * (the dock and the open home page never leave composition on a HOME app).
 *
 * Both counters only ever increase, so a `global + perPackage` sum never collides with an older key.
 */
data class IconEpochs(
    val global: Int = 0,
    val perPackage: Map<String, Int> = emptyMap(),
) {
    /** The cache-key epoch for [packageName]'s icons. */
    fun of(packageName: String): Int = global + (perPackage[packageName] ?: 0)

    /** A copy with [packageNames]'s epochs bumped — those packages' icons may have changed. */
    fun bump(packageNames: Iterable<String>): IconEpochs =
        copy(perPackage = perPackage + packageNames.map { it to (perPackage[it] ?: 0) + 1 })

    /** A copy with the global generation bumped — every icon may have changed (icon pack update). */
    fun bumpGlobal(): IconEpochs = copy(global = global + 1)
}
