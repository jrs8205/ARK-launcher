package org.arkikeskus.launcher.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.arkikeskus.launcher.model.SearchResult
import org.arkikeskus.launcher.model.SearchResults
import javax.inject.Inject

/**
 * Runs all enabled [SearchProvider]s concurrently for a query and groups their results into
 * [SearchResults]. A blank query short-circuits to empty (no provider runs); a provider that
 * throws or is disabled contributes nothing (never breaks the whole search).
 */
class SearchAggregator @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards SearchProvider>,
) {
    suspend fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.isBlank()) return SearchResults()
        val all = coroutineScope {
            providers.map { p ->
                async(Dispatchers.Default) {
                    runCatching { if (p.isEnabled()) p.query(q) else emptyList() }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        return SearchResults(
            calc = all.filterIsInstance<SearchResult.Calculation>().firstOrNull(),
            apps = all.filterIsInstance<SearchResult.App>().filterNot { it.privateProfile }.map { it.app },
            settings = all.filterIsInstance<SearchResult.Setting>(),
            contacts = all.filterIsInstance<SearchResult.Contact>(),
            privateApps = all.filterIsInstance<SearchResult.App>().filter { it.privateProfile }.map { it.app },
            privateSpaceEntry = all.any { it is SearchResult.PrivateSpaceEntry },
        )
    }
}
