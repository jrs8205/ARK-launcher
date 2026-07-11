package org.arkikeskus.launcher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.di.ApplicationScope
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepository @Inject constructor(
    private val source: LauncherAppsSource,
    private val settingsRepository: SettingsRepository,
    private val appUsageRepository: AppUsageRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /**
     * All launchable apps, sorted by (effective) label, updating on install/remove/change. Any
     * user-chosen custom label (see [SettingsRepository.customLabels]) is applied over the system
     * label so it shows everywhere apps are rendered, and re-sorted to stay alphabetical.
     */
    val apps: Flow<List<AppItem>> = combine(source.appsFlow(), settingsRepository.customLabels) { apps, labels ->
        if (labels.isEmpty()) {
            apps
        } else {
            apps
                .map { a -> labels[a.key]?.let { a.copy(label = it) } ?: a }
                .sortedBy { it.label.lowercase() }
        }
    }

    /** See [LauncherAppsSource.packageEvents]. */
    val packageEvents: Flow<String> get() = source.packageEvents

    /** Launches [appItem]; on success, records the launch for the "most used" ranking (fire-and-forget). */
    fun launch(appItem: AppItem): Result<Unit> {
        val result = source.launch(appItem)
        if (result.isSuccess) scope.launch { appUsageRepository.recordLaunch(appItem.key) }
        return result
    }
}
