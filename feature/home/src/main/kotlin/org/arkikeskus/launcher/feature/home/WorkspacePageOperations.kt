package org.arkikeskus.launcher.feature.home

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.model.LauncherSettings

/** Serializes the page menu, including reads from storage. UI flows may lag behind a write. */
internal class WorkspacePageOperations(
    private val settings: suspend () -> LauncherSettings,
    private val occupiedPages: suspend () -> Set<Int>,
    private val insertRows: suspend (Int) -> Unit,
    private val removeRows: suspend (Int) -> Boolean,
    private val save: suspend (home: Int, count: Int) -> Unit,
) {
    private val mutex = Mutex()

    private suspend fun pageCount(s: LauncherSettings): Int =
        maxOf((occupiedPages().maxOrNull() ?: 0) + 1, s.homePageCount)
            .coerceIn(1, HomeLayoutRepository.MAX_PAGES)

    suspend fun insert(at: Int): Int = mutex.withLock {
        val s = settings()
        val count = pageCount(s)
        if (count >= HomeLayoutRepository.MAX_PAGES) return@withLock -1
        val index = at.coerceIn(0, count)
        val home = s.homePage.coerceIn(0, count - 1)
        // A disappearing UI coroutine must not abandon the counters after shifting the rows.
        withContext(NonCancellable) {
            insertRows(index)
            save(if (home >= index) home + 1 else home, explicitPageCountAfterInsert(s.homePageCount, index))
        }
        index
    }

    /** Returns the destination calculated from this operation's snapshot, or null on rejection. */
    suspend fun remove(page: Int): Int? = mutex.withLock {
        val s = settings()
        val count = pageCount(s)
        if (count <= 1 || page !in 0 until count) return@withLock null
        val target = page.coerceAtMost(count - 2)
        val home = s.homePage.coerceIn(0, count - 1)
        withContext(NonCancellable) {
            if (!removeRows(page)) return@withContext null
            save(
                when {
                    home > page -> home - 1
                    home == page -> target
                    else -> home
                },
                explicitPageCountAfterRemove(s.homePageCount, page),
            )
            target
        }
    }

    suspend fun setHome(page: Int) = mutex.withLock {
        val s = settings()
        save(page.coerceIn(0, pageCount(s) - 1), s.homePageCount)
    }
}
