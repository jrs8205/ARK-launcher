package org.arkikeskus.launcher.model

/**
 * Coil model for rendering an app icon. [themed] and [dark] are part of the model — and therefore the
 * cache key — so toggling themed icons or switching the dark/light theme re-renders the icon
 * immediately and caches each variant separately.
 *
 * [themed] only changes apps whose adaptive icon ships a monochrome layer (Android 13+); every other
 * app falls back to its normal icon, exactly like Pixel Launcher, unless [themeAll] asks for a
 * one-colour silhouette of its icon instead (a Telegram request). Part of the key like the rest.
 *
 * [iconPack] is the package name of a selected third-party icon pack (empty = none). When set it takes
 * priority over [themed]: mapped apps use the pack's drawable, unmapped apps are masked to the pack's
 * style. It's part of the model/cache key so switching packs re-renders every icon.
 *
 * [epoch] is the app's [IconEpochs] token. It's part of the model/cache key so a package update
 * re-renders that app's icon immediately — including icons already on screen, whose AsyncImage only
 * re-fetches when the model changes.
 *
 * [shape] is the icon shape the user picked (issue #26); the system's means the icon is drawn as the phone
 * shapes it. Part of the key, so picking a shape redraws every icon. An icon from an icon pack keeps the
 * form its pack gave it.
 */
data class IconRequest(
    val app: AppItem,
    val themed: Boolean = false,
    val dark: Boolean = false,
    val iconPack: String = "",
    val epoch: Int = 0,
    val themeAll: Boolean = false,
    val shape: IconShape = IconShape.SYSTEM,
)
