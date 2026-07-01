package org.arkikeskus.launcher.model

/**
 * Coil model for rendering an app icon. [themed] and [dark] are part of the model — and therefore the
 * cache key — so toggling themed icons or switching the dark/light theme re-renders the icon
 * immediately and caches each variant separately.
 *
 * [themed] only changes apps whose adaptive icon ships a monochrome layer (Android 13+); every other
 * app falls back to its normal icon, exactly like Pixel Launcher.
 *
 * [iconPack] is the package name of a selected third-party icon pack (empty = none). When set it takes
 * priority over [themed]: mapped apps use the pack's drawable, unmapped apps are masked to the pack's
 * style. It's part of the model/cache key so switching packs re-renders every icon.
 */
data class IconRequest(
    val app: AppItem,
    val themed: Boolean = false,
    val dark: Boolean = false,
    val iconPack: String = "",
)
