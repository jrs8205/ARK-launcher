package org.arkikeskus.launcher.data

import kotlinx.coroutines.flow.Flow
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the app shortcuts placed on the (paged, free-cell) home screen (Room). */
@Singleton
class HomeLayoutRepository @Inject constructor(
    private val dao: HomeItemDao,
) {
    val homeItems: Flow<List<HomeItemEntity>> = dao.observeAll()

    suspend fun addToHome(appItem: AppItem, columns: Int) {
        if (dao.count(appItem.packageName, appItem.className, appItem.userSerial) > 0) return
        val (page, cellX, cellY) = firstFreeCell(dao.getAll(), columns)
        dao.insert(
            HomeItemEntity(
                packageName = appItem.packageName,
                className = appItem.className,
                userSerial = appItem.userSerial,
                page = page,
                cellX = cellX,
                cellY = cellY,
            ),
        )
    }

    suspend fun moveItem(appItem: AppItem, page: Int, cellX: Int, cellY: Int) {
        dao.move(appItem.packageName, appItem.className, appItem.userSerial, page, cellX, cellY)
    }

    suspend fun removeFromHome(appItem: AppItem) {
        dao.deleteByKey(appItem.packageName, appItem.className, appItem.userSerial)
    }

    private fun firstFreeCell(items: List<HomeItemEntity>, columns: Int): Triple<Int, Int, Int> {
        val occupied = items.map { Triple(it.page, it.cellX, it.cellY) }.toHashSet()
        var page = 0
        while (true) {
            for (y in 0 until ROWS) {
                for (x in 0 until columns) {
                    if (Triple(page, x, y) !in occupied) return Triple(page, x, y)
                }
            }
            page++
        }
    }

    companion object {
        /** Rows per home page (fixed for now). */
        const val ROWS = 6
    }
}
