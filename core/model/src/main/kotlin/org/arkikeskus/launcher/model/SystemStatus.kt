package org.arkikeskus.launcher.model

enum class WifiBand { GHZ_2_4, GHZ_5, GHZ_6, UNKNOWN }

data class BatteryStatus(
    val percent: Int,
    val charging: Boolean,
)

data class WifiStatus(
    val connected: Boolean,
    val level: Int, // 0..4
    val band: WifiBand,
)

data class MobileStatus(
    val active: Boolean,
    val level: Int, // 0..4
    val generation: String?, // "5G" / "4G" / "3G" / null when unknown or no permission
)

/** Status-bar flags. Only the ones currently active are shown. */
data class SystemFlags(
    val airplane: Boolean = false,
    val silent: Boolean = false,
    val vibrate: Boolean = false,
    val bluetooth: Boolean = false,
    val alarm: Boolean = false,
    val location: Boolean = false,
    val nfc: Boolean = false,
    val vpn: Boolean = false,
    val dataSaver: Boolean = false,
    val dnd: Boolean = false,
)
