package org.arkikeskus.launcher.data.search

import kotlinx.coroutines.flow.first
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.SearchResult
import javax.inject.Inject

/**
 * Matches launchable apps by (effective) label. Hidden apps stay out of the results, as they stay out of the
 * drawer's lists, unless the user has asked the search to find them too
 * ([org.arkikeskus.launcher.model.LauncherSettings.searchHiddenApps], discussion #34). Snapshots the current
 * apps, the hidden set and that setting per query.
 */
class AppSearchProvider @Inject constructor(
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
) : SearchProvider {

    override suspend fun isEnabled(): Boolean = true

    override suspend fun query(query: String): List<SearchResult> = matchingApps(
        apps = appRepository.apps.first(),
        query = query,
        hidden = settingsRepository.hiddenApps.first(),
        findHidden = settingsRepository.settings.first().searchHiddenApps,
    ).map { SearchResult.App(it) }
}

/**
 * The apps of [apps] whose name has [query] in it (capitals aside, spaces around the query aside), in the
 * order given. An app of [hidden] is among them only when the search is to find hidden apps too ([findHidden]).
 */
internal fun matchingApps(apps: List<AppItem>, query: String, hidden: Set<String>, findHidden: Boolean): List<AppItem> {
    val q = query.trim()
    return apps.filter { (findHidden || it.key !in hidden) && it.label.contains(q, ignoreCase = true) }
}
