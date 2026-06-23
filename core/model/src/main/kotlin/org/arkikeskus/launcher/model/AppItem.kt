package org.arkikeskus.launcher.model

import android.content.ComponentName
import android.os.UserHandle

/**
 * A launchable application entry, as surfaced by [android.content.pm.LauncherApps].
 * Carries the [UserHandle] so apps in work/private profiles can be launched correctly.
 */
data class AppItem(
    val packageName: String,
    val className: String,
    val user: UserHandle,
    val userSerial: Long,
    val label: String,
    val isHidden: Boolean = false,
) {
    val componentName: ComponentName get() = ComponentName(packageName, className)

    /** Stable identity for list keys and icon cache keys (unique across user profiles). */
    val key: String get() = "$packageName/$className/$userSerial"
}
