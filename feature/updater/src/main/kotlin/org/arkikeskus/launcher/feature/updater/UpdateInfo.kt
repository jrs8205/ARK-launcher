package org.arkikeskus.launcher.feature.updater

/** A newer release available on GitHub. */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)
