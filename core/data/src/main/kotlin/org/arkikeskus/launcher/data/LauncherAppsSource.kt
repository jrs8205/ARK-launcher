package org.arkikeskus.launcher.data

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
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
import coil3.ImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.IconEpochs
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Wraps [LauncherApps]: streams the installed launchable apps (reacting to install/remove/change),
 * launches apps, and resolves their icons.
 */
@Singleton
class LauncherAppsSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iconPacks: IconPackRepository,
    // Provider breaks the instantiation cycle: the ImageLoader itself is built with this source.
    private val imageLoader: Provider<ImageLoader>,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    /** Package names from LauncherApps callbacks — lets feature-layer caches (e.g. resolved pinned
     *  shortcuts) invalidate per package instead of guessing from full app-list reloads. */
    private val _packageEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val packageEvents: SharedFlow<String> = _packageEvents

    /** (package, profile serial) from onPackageRemoved — a DEFINITIVE uninstall for that profile.
     *  Updates fire onPackageChanged and storage ejection onPackagesUnavailable, so neither can
     *  reach this flow and trigger a wrongful home-row cleanup. */
    private val _packageRemovals = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 16)
    val packageRemovals: SharedFlow<Pair<String, Long>> = _packageRemovals

    /** Re-fetch tokens for app icons (see [IconEpochs]): a package update bumps that package, an
     *  icon-pack content change bumps the global generation. AppIcon feeds these into the Coil
     *  model/cache key, so even icons already on screen re-fetch immediately. */
    private val _iconEpochs = MutableStateFlow(IconEpochs())
    val iconEpochs: StateFlow<IconEpochs> = _iconEpochs

    /** Fail-safe install check for the ghost-row sweep: only a definitive "name not found" (or a
     *  removed profile) answers false — any other failure keeps the row (a locked work profile or
     *  a transient Binder error must never wipe real items). */
    fun isAppInstalled(packageName: String, userSerial: Long): Boolean {
        val user = runCatching { userManager?.getUserForSerialNumber(userSerial) }.getOrNull()
            ?: return false // the whole profile is gone → its rows are stale
        val result = runCatching { launcherApps.getApplicationInfo(packageName, 0, user) }
        return result.isSuccess || result.exceptionOrNull() !is PackageManager.NameNotFoundException
    }

    fun appsFlow(): Flow<List<AppItem>> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())

        // One serial worker fed by a conflated trigger, NOT a coroutine per event: overlapping
        // queryApps() calls could complete out of order, leaving the flow's latest value a stale
        // snapshot after a burst of package events (install storms, batch updates). Serializing
        // guarantees the newest scan is emitted last; conflation coalesces the burst into one rescan.
        val reloads = Channel<Unit>(Channel.CONFLATED)
        fun reload() { reloads.trySend(Unit) }
        launch(Dispatchers.IO) {
            for (unused in reloads) trySend(queryApps())
        }

        fun packageEvent(vararg packageNames: String, iconsMayHaveChanged: Boolean = false) {
            packageNames.forEach { _packageEvents.tryEmit(it) }
            // An updated/replaced package bumps its icon epoch: the epoch is in the Coil model/cache
            // key, so the changed app's icon re-fetches everywhere — including AsyncImages already on
            // screen (dock, open home page, drawer top), which only re-launch when the model changes.
            // A memory-cache clear alone left those painters showing the OLD icon until process death.
            if (iconsMayHaveChanged) {
                _iconEpochs.update { it.bump(packageNames.asIterable()) }
            }
            // A changed icon PACK invalidates every mapped/masked icon, not just its own package:
            // bump the global generation (re-keys all icons) and drop the now-dead cache entries.
            if (packageNames.any { iconPacks.invalidate(it) }) {
                _iconEpochs.update { it.bumpGlobal() }
                runCatching { imageLoader.get().memoryCache?.clear() }
            }
            reload()
        }

        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = packageEvent(packageName)
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                val serial = runCatching { userManager?.getSerialNumberForUser(user) }.getOrNull() ?: 0L
                _packageRemovals.tryEmit(packageName to serial)
                packageEvent(packageName)
            }
            override fun onPackageChanged(packageName: String, user: UserHandle) =
                packageEvent(packageName, iconsMayHaveChanged = true)
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) =
                packageEvent(*names, iconsMayHaveChanged = replacing)
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) =
                packageEvent(*names)
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
     * The launcher entry the system lists first for [packageName] in [user] — the one the package's
     * launch intent resolves to when it declares several MAIN/LAUNCHER activities. Resolved through
     * [LauncherApps] so work-profile packages resolve in their own profile. Null when the package has
     * no launcher entry or the profile is unavailable.
     */
    fun launchClassName(packageName: String, user: UserHandle): String? = runCatching {
        launcherApps.getActivityList(packageName, user).firstOrNull()?.componentName?.className
    }.getOrNull()

    /**
     * Resolves [appItem]'s icon. Like [launch], this must never crash the process (the launcher is the
     * device HOME): the app can be removed mid-load or its profile locked, so any failure resolves to
     * a null icon instead of propagating.
     *
     * A selected [iconPack] takes priority: a mapped app uses the pack's drawable, an unmapped app keeps
     * its normal icon. Otherwise, when [themed] is set (and the device + app support it) the Material You
     * monochrome icon is returned, tinted for the [dark]/light theme; else the normal icon.
     */
    fun loadIcon(
        appItem: AppItem,
        themed: Boolean = false,
        dark: Boolean = false,
        iconPack: String = "",
    ): Drawable? = runCatching {
        val info = launcherApps.getActivityList(appItem.packageName, appItem.user)
            .firstOrNull { it.componentName.className == appItem.className }
            ?: return@runCatching null
        // A selected icon pack overrides themed icons: use the pack's drawable if it maps this app,
        // otherwise fall through to the normal icon below.
        if (iconPack.isNotBlank()) {
            iconPacks.get(iconPack)?.getIcon(appItem.componentName)?.let { return@runCatching it }
        } else if (themed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
