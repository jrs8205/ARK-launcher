package org.arkikeskus.launcher.data

import kotlinx.coroutines.flow.Flow
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the app shortcuts placed on the home screen (Room). */
@Singleton
class HomeLayoutRepository @Inject constructor(
    private val dao: HomeItemDao,
) {
    val homeItems: Flow<List<HomeItemEntity>> = dao.observeAll()

    suspend fun addToHome(appItem: AppItem) {
        if (dao.count(appItem.packageName, appItem.className, appItem.userSerial) == 0) {
            dao.insert(
                HomeItemEntity(
                    packageName = appItem.packageName,
                    className = appItem.className,
                    userSerial = appItem.userSerial,
                    position = dao.maxPosition() + 1,
                ),
            )
        }
    }

    suspend fun removeFromHome(appItem: AppItem) {
        dao.deleteByKey(appItem.packageName, appItem.className, appItem.userSerial)
    }
}
