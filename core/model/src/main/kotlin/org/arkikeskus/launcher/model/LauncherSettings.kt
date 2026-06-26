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
)
