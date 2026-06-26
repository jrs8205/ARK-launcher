package org.arkikeskus.launcher.data.search

import kotlinx.coroutines.flow.first
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.SearchResult
import javax.inject.Inject

/**
 * Matches launchable apps by (effective) label, excluding hidden apps — preserving the drawer's
 * existing rule that hidden apps stay out of search results. Snapshots the current app + hidden
 * sets per query.
 */
class AppSearchProvider @Inject constructor(
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
) : SearchProvider {

    override suspend fun isEnabled(): Boolean = true

    override suspend fun query(query: String): List<SearchResult> {
        val q = query.trim()
        val hidden = settingsRepository.hiddenApps.first()
        return appRepository.apps.first()
            .asSequence()
            .filterNot { it.key in hidden }
            .filter { it.label.contains(q, ignoreCase = true) }
            .map { SearchResult.App(it) }
            .toList()
    }
}
