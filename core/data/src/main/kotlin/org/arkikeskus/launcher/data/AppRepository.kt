package org.arkikeskus.launcher.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepository @Inject constructor(
    private val source: LauncherAppsSource,
    private val settingsRepository: SettingsRepository,
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

    fun launch(appItem: AppItem): Result<Unit> = source.launch(appItem)
}
