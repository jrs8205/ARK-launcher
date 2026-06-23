package org.arkikeskus.launcher.model

/** User-configurable launcher settings (persisted in DataStore). */
data class LauncherSettings(
    val dockEnabled: Boolean = true,
    val dockColumns: Int = 4,
    val homeColumns: Int = 4,
    val drawerColumns: Int = 4,
    val swipeUpForDrawer: Boolean = true,
    val swipeDownForNotifications: Boolean = true,
)
