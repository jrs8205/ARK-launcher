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
    private val removeRows: suspend (page: Int, purge: Boolean) -> Boolean,
    private val save: suspend (home: Int, count: Int) -> Unit,
) {
    private val mutex = Mutex()

    private suspend fun pageCount(s: LauncherSettings): Int = permanentPageCount(occupiedPages(), s.homePageCount)

    suspend fun insert(at: Int): Int = mutex.withLock {
        val s = settings()
        val count = pageCount(s)
        if (count >= HomeLayoutRepository.MAX_PAGES) return@withLock -1
        val index = at.coerceIn(0, count)
        val home = s.homePage.coerceIn(0, count - 1)
        // A disappearing UI coroutine must not abandon the counters after shifting the rows, and a
        // counter write that fails must not leave the rows shifted: the new page is closed again.
        withContext(NonCancellable) {
            insertRows(index)
            try {
                save(if (home >= index) home + 1 else home, explicitPageCountAfterInsert(s.homePageCount, index))
            } catch (e: Exception) {
                runCatching { removeRows(index, false) }
                throw e
            }
        }
        index
    }

    /** Returns the destination calculated from this operation's snapshot, or null on rejection.
     *  [purge] deletes rows the caller knows the home screen cannot show; see removeEmptyPage. */
    suspend fun remove(page: Int, purge: Boolean = false): Int? = mutex.withLock {
        val s = settings()
        val count = pageCount(s)
        if (count <= 1 || page !in 0 until count) return@withLock null
        val target = page.coerceAtMost(count - 2)
        val home = s.homePage.coerceIn(0, count - 1)
        withContext(NonCancellable) {
            if (!removeRows(page, purge)) return@withContext null
            try {
                save(
                    when {
                        home > page -> home - 1
                        home == page -> target
                        else -> home
                    },
                    explicitPageCountAfterRemove(s.homePageCount, page),
                )
            } catch (e: Exception) {
                // Reopen the gap so the rows match the counters that were never written.
                runCatching { insertRows(page) }
                throw e
            }
            target
        }
    }

    suspend fun setHome(page: Int) = mutex.withLock {
        val s = settings()
        save(page.coerceIn(0, pageCount(s) - 1), s.homePageCount)
    }
}
