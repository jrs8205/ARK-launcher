package org.arkikeskus.launcher.data

import kotlinx.coroutines.flow.Flow
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepository @Inject constructor(
    private val source: LauncherAppsSource,
) {
    /** All launchable apps, sorted by label, updating on install/remove/change. */
    val apps: Flow<List<AppItem>> = source.appsFlow()

    fun launch(appItem: AppItem) = source.launch(appItem)
}
