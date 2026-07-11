package org.arkikeskus.launcher.feature.updater

/** A newer release available on GitHub. */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Lower-case hex from the asset's `digest` field; null on releases published before GitHub added it. */
    val sha256: String? = null,
)
