package org.arkikeskus.launcher.model

/** User-configurable launcher settings (persisted in DataStore). */
data class LauncherSettings(
    val dockEnabled: Boolean = true,
    val dockColumns: Int = 4,
    val homeColumns: Int = 4,
    val drawerColumns: Int = 4,
    val showDrawerSearch: Boolean = true,
    val swipeUpForDrawer: Boolean = true,
    val swipeDownForNotifications: Boolean = true,
    val showDockLabels: Boolean = false,
    val showHomeLabels: Boolean = true,
    val showDrawerLabels: Boolean = true,
    val dockBackgroundOpacity: Float = 0.35f,
    val showPageIndicator: Boolean = true,
    val showNotificationDots: Boolean = true,
    /** When notification dots are on: true shows the count (Nova-style), false shows a plain dot. */
    val notificationDotCount: Boolean = true,
    /** Size multiplier for the notification dot/badge (1.0 = default). */
    val notificationDotScale: Float = 1.0f,
    /** Render app icons as Material You themed (monochrome) icons where the app provides one. */
    val useThemedIcons: Boolean = false,
    /** Package name of the selected third-party icon pack (empty = system default). Overrides
     *  [useThemedIcons] when set: mapped apps use the pack's icon, unmapped apps are masked to its style. */
    val iconPackPackage: String = "",
    /** Include contacts in app-drawer search (gated by READ_CONTACTS; requested when enabled). */
    val searchContacts: Boolean = false,
    /** App key launched by the left-edge home swipe (Settings ▸ Eleet); blank = gesture disabled. */
    val leftSwipeAppKey: String = "",
    /** Lock the desktop layout: when true, home + dock items can't be moved, removed, or added. */
    val desktopLocked: Boolean = false,
    /** Show a "most used" row (top apps by decayed launch frequency) above the drawer's app list. */
    val showFrequentApps: Boolean = false,
    /** Size multiplier for app icon labels across home/dock/drawer/folders (1.0 = the default 11sp). */
    val appLabelTextScale: Float = 1.0f,
    /** ARGB color for app icon labels on the home surfaces (home/dock/folders); default white. The
     *  app drawer keeps its theme color for readability over its solid background. */
    val appLabelColor: Int = 0xFFFFFFFF.toInt(),
    /** Show a slim status bar (clock + battery + signal, with dynamic battery/signal colors) at the top
     *  of the home screen. */
    val showStatusBar: Boolean = false,
    /** Hide the system status bar while the launcher is in the foreground (immersive/fullscreen home).
     *  Independent of [showStatusBar]; combine the two to replace the system bar with the themed one. */
    val hideSystemStatusBar: Boolean = false,
    /** Darkness of the scrim drawn behind the themed status bar (0 = none, 1 = solid black); keeps the
     *  clock/indicators legible over bright wallpapers. Own setting, like [dockBackgroundOpacity]. */
    val statusBarScrimOpacity: Float = 0.6f,
)
