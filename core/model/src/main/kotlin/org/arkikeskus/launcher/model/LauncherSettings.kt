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
)
