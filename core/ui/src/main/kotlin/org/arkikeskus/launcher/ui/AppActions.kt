package org.arkikeskus.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import org.arkikeskus.launcher.model.AppItem

/**
 * System app actions reachable from the long-press menus, targeted at the app's own user profile.
 *
 * A package can be installed in several profiles (personal, work, Private Space); a bare package
 * name doesn't identify which one, so these take the full [AppItem] and route through the
 * profile-aware [LauncherApps] APIs. Every call is wrapped in `runCatching` because an OS-blocked
 * profile action must never crash the launcher (it is the device's HOME).
 */
object AppActions {

    /** Opens the OS app-details screen for [appItem] in its own profile. */
    fun openAppInfo(context: Context, appItem: AppItem) {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        var opened = launcherApps != null && runCatching {
            launcherApps.startAppDetailsActivity(appItem.componentName, appItem.user, null, null)
        }.isSuccess
        // Fallback for the personal profile ONLY: a bare package intent always resolves in the
        // launcher's own profile, so for a work / Private Space app it would open (and offer to
        // uninstall) the personal copy of the same package instead.
        if (!opened && appItem.user == Process.myUserHandle()) {
            opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", appItem.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        }
        if (!opened) {
            runCatching { Toast.makeText(context, R.string.app_info_unavailable, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Uninstalls [appItem]. The personal profile uses the system uninstall dialog directly; other
     * profiles (work / Private Space) have no reliable public profile-aware uninstall intent, so we
     * open that profile's app-details screen instead, from where the OS offers removal. This avoids
     * silently targeting (or uninstalling) the wrong profile's copy of the same package.
     */
    fun uninstall(context: Context, appItem: AppItem) {
        if (appItem.user == Process.myUserHandle()) {
            val started = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_DELETE)
                        .setData(Uri.fromParts("package", appItem.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (!started) openAppInfo(context, appItem)
        } else {
            openAppInfo(context, appItem)
        }
    }
}
