package org.arkikeskus.launcher.feature.settings

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * "ARK-launcher 0.9.4 (61)": the installed build as the package manager reports it — app name,
 * version name and version code — so the bottom of Settings tells which version this is without a
 * trip to the system's app info. A debuggable build adds "-debug" to the version name: it installs
 * beside the release under its own package id and looks the same otherwise. No lookup of what is
 * newest: that stays with Updates ▸ Check for updates. Null only if the package manager cannot
 * describe the running package.
 */
internal fun installedVersionLabel(context: Context): String? = runCatching {
    val pm = context.packageManager
    @Suppress("DEPRECATION") // the flag-less overload is the one every supported Android has
    val info = pm.getPackageInfo(context.packageName, 0)
    val app = info.applicationInfo ?: return@runCatching null
    val debug = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val version = (info.versionName ?: "?") + if (debug) "-debug" else ""
    "${app.loadLabel(pm)} $version (${info.longVersionCode})"
}.getOrNull()
