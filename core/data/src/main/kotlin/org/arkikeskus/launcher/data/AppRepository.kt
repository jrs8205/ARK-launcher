package org.arkikeskus.launcher.data

import android.graphics.Rect
import android.os.Bundle
import android.os.UserHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.di.ApplicationScope
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepository @Inject constructor(
    private val source: LauncherAppsSource,
    private val settingsRepository: SettingsRepository,
    private val appUsageRepository: AppUsageRepository,
    profiles: WorkProfileRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /**
     * All launchable apps, sorted by (effective) label, updating on install/remove/change. Any
     * user-chosen custom label (see [SettingsRepository.customLabels]) is applied over the system
     * label so it shows everywhere apps are rendered, and re-sorted to stay alphabetical; a picked
     * icon ([SettingsRepository.customIcons]) rides along the same way.
     *
     * Shared (Eagerly, replay 1): the upstream is a cold callbackFlow that registers a
     * LauncherApps.Callback and scans every profile's activities per collector — and this flow has
     * 3–4 permanent collectors (home, the two drawer combines, the notifications widget) plus a
     * `.first()` per search keystroke. Unshared, ONE package update on the device triggered 3–4
     * duplicate Binder scans and every search keystroke re-registered + re-scanned the world.
     * Eagerly (not WhileSubscribed) keeps the single callback registered for the process lifetime —
     * a HOME process — so package events (and their icon-epoch bumps) are never missed while
     * collectors are briefly detached.
     */
    val apps: Flow<List<AppItem>> = combine(
        // A profile added, removed or toggled is not a package event: re-query on its broadcast.
        source.appsFlow(reloadSignals = profiles.changes),
        settingsRepository.customLabels,
        settingsRepository.customIcons,
    ) { apps, labels, icons ->
        val withIcons = if (icons.isEmpty()) apps else apps.map { a -> icons[a.key]?.let { a.copy(customIcon = it) } ?: a }
        if (labels.isEmpty()) {
            withIcons
        } else {
            withIcons
                .map { a -> labels[a.key]?.let { a.copy(label = it) } ?: a }
                .sortedBy { it.label.lowercase() }
        }
    }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** See [LauncherAppsSource.packageEvents] (batches, for one collector). */
    val packageEvents: Flow<Set<String>> get() = source.packageEvents

    /** See [LauncherAppsSource.packageRemovals] (batches, for one collector). */
    val packageRemovals: Flow<Set<Pair<String, Long>>> get() = source.packageRemovals

    /** See [LauncherAppsSource.shortcutChanges] (batches, for one collector). */
    val shortcutChanges: Flow<Set<Pair<String, Long>>> get() = source.shortcutChanges

    /** See [LauncherAppsSource.isAppInstalled] (fail-safe: unknown → true). */
    fun isAppInstalled(packageName: String, userSerial: Long): Boolean =
        source.isAppInstalled(packageName, userSerial)

    /** Points the app keys kept in the settings (dock, hidden apps, custom labels, drawer folders,
     *  the left-swipe app) at an app's new launcher activity after it renamed or swapped it — see
     *  [renamedAppKeys]. Run once per launch, next to the home rows' sweep. */
    suspend fun followRenamedActivitiesInSettings() {
        val renames = withContext(Dispatchers.IO) {
            renamedAppKeys(settingsRepository.referencedAppKeys(), source)
        }
        settingsRepository.renameAppKeys(renames)
    }

    /** See [LauncherAppsSource.launchClassName]. */
    fun launchClassName(packageName: String, user: UserHandle): String? = source.launchClassName(packageName, user)

    /** Launches [appItem] (from [sourceBounds] with [options], see [LauncherAppsSource.launch]); on
     *  success, records the launch for the "most used" ranking (fire-and-forget). */
    fun launch(appItem: AppItem, sourceBounds: Rect? = null, options: Bundle? = null): Result<Unit> {
        val result = source.launch(appItem, sourceBounds, options)
        if (result.isSuccess) scope.launch { appUsageRepository.recordLaunch(appItem.key) }
        return result
    }
}
