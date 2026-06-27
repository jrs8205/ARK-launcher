package org.arkikeskus.launcher.data

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [LauncherApps]: streams the installed launchable apps (reacting to install/remove/change),
 * launches apps, and resolves their icons.
 */
@Singleton
class LauncherAppsSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    fun appsFlow(): Flow<List<AppItem>> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())

        fun reload() {
            launch(Dispatchers.IO) { trySend(queryApps()) }
        }

        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
            override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
            override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        }

        launcherApps.registerCallback(callback, handler)
        reload()
        awaitClose { launcherApps.unregisterCallback(callback) }
    }

    private fun queryApps(): List<AppItem> {
        val profiles = runCatching { userManager?.userProfiles }.getOrNull() ?: listOf(Process.myUserHandle())
        return profiles.flatMap { user ->
            val serial = runCatching { userManager?.getSerialNumberForUser(user) }.getOrNull() ?: 0L
            val list = runCatching {
                launcherApps.getActivityList(null, user)
            }.getOrElse { emptyList() }
            list.map { info ->
                AppItem(
                    packageName = info.componentName.packageName,
                    className = info.componentName.className,
                    user = user,
                    userSerial = serial,
                    label = info.label?.toString().orEmpty(),
                )
            }
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Launches [appItem]. A launcher is the device's HOME, so a single failed launch (app removed
     * mid-tap, profile locked, activity no longer launchable) must never crash the process — the
     * error is captured in the [Result] for the caller to log or surface.
     */
    fun launch(appItem: AppItem): Result<Unit> = runCatching {
        launcherApps.startMainActivity(appItem.componentName, appItem.user, null, null)
    }

    /**
     * Resolves [appItem]'s icon. Like [launch], this must never crash the process (the launcher is the
     * device HOME): the app can be removed mid-load or its profile locked, so any failure resolves to
     * a null icon instead of propagating.
     *
     * When [themed] is set (and the device + app support it) the Material You monochrome icon is
     * returned, tinted for the [dark]/light theme; otherwise the normal badged icon is used.
     */
    fun loadIcon(appItem: AppItem, themed: Boolean = false, dark: Boolean = false): Drawable? = runCatching {
        val info = launcherApps.getActivityList(appItem.packageName, appItem.user)
            .firstOrNull { it.componentName.className == appItem.className }
            ?: return@runCatching null
        if (themed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            themedIcon(info, dark)?.let { return@runCatching it }
        }
        info.getBadgedIcon(0)
    }.getOrNull()

    /**
     * Builds a Material You themed icon from [info]'s monochrome layer, or null if it has none (most
     * non-Google apps). The monochrome drawable is the adaptive-icon foreground glyph; we tint it and
     * composite it over a solid Monet-coloured background as a fresh AdaptiveIconDrawable, so the
     * platform applies the icon mask and foreground scaling for us.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun themedIcon(info: LauncherActivityInfo, dark: Boolean): Drawable? {
        val adaptive = info.getIcon(0) as? AdaptiveIconDrawable ?: return null
        val mono = (adaptive.monochrome ?: return null).mutate()
        val (bg, fg) = themedColors(dark)
        mono.setTint(fg)
        return AdaptiveIconDrawable(ColorDrawable(bg), mono)
    }

    /** Background/foreground colours for themed icons, from the system dynamic (Monet) palette. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun themedColors(dark: Boolean): Pair<Int, Int> = if (dark) {
        context.getColor(android.R.color.system_neutral1_800) to
            context.getColor(android.R.color.system_accent1_100)
    } else {
        context.getColor(android.R.color.system_accent1_100) to
            context.getColor(android.R.color.system_neutral2_700)
    }
}
