package org.arkikeskus.launcher.data.local

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.profile.ActiveHomeProfile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Names the home profile the layout calls made inside a block go to, whatever profile is on: carried in
 * the coroutine's context, so it holds through a Room transaction and every call the block makes.
 */
class HomeProfileScope(val id: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HomeProfileScope>
}

/** Runs [block] with its layout calls ([HomeItemDao]) going to the home profile [id]. */
suspend fun <T> inHomeProfile(id: Long, block: suspend () -> T): T = withContext(HomeProfileScope(id)) { block() }

/**
 * [HomeItemDao] over the table: each call is made in the home profile named around it ([inHomeProfile]),
 * or else in the one that is on. The profile is read once per call; work of several calls that must stay
 * in one profile names it around itself (the layout's operations do, see `HomeLayoutRepository`).
 */
class ProfileHomeItemDao(
    private val rows: HomeItemRoomDao,
    private val active: ActiveHomeProfile,
) : HomeItemDao {

    override suspend fun resolvedProfile(): Long = coroutineContext[HomeProfileScope]?.id ?: active.current()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<HomeItemEntity>> = active.id.flatMapLatest { rows.observeAll(it) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAllWithProfile(): Flow<Pair<Long, List<HomeItemEntity>>> =
        active.id.flatMapLatest { profile -> rows.observeAll(profile).map { profile to it } }

    override suspend fun getContainerOrdered(containerId: Long) = rows.getContainerOrdered(resolvedProfile(), containerId)

    override suspend fun getContainer(containerId: Long) = rows.getContainer(resolvedProfile(), containerId)

    override suspend fun getAll() = rows.getAll(resolvedProfile())

    override suspend fun getById(id: Long) = rows.getById(resolvedProfile(), id)

    override suspend fun getByKey(containerId: Long, pkg: String, cls: String, user: Long) =
        rows.getByKey(resolvedProfile(), containerId, pkg, cls, user)

    override suspend fun getAt(containerId: Long, page: Int, cellX: Int, cellY: Int) =
        rows.getAt(resolvedProfile(), containerId, page, cellX, cellY)

    override suspend fun insert(item: HomeItemEntity) = rows.insert(item.copy(homeProfileId = resolvedProfile()))

    override suspend fun deleteById(id: Long) = rows.deleteById(resolvedProfile(), id)

    override suspend fun deleteByKey(containerId: Long, pkg: String, cls: String, user: Long) =
        rows.deleteByKey(resolvedProfile(), containerId, pkg, cls, user)

    override suspend fun clear() = rows.clear(resolvedProfile())

    override suspend fun count(containerId: Long, pkg: String, cls: String, user: Long) =
        rows.count(resolvedProfile(), containerId, pkg, cls, user)

    override suspend fun childCount(containerId: Long) = rows.childCount(resolvedProfile(), containerId)

    override suspend fun moveById(id: Long, containerId: Long, page: Int, cellX: Int, cellY: Int) =
        rows.moveById(resolvedProfile(), id, containerId, page, cellX, cellY)

    override suspend fun offsetPages(containerId: Long, from: Int, offset: Int) =
        rows.offsetPages(resolvedProfile(), containerId, from, offset)

    override suspend fun countOnPage(containerId: Long, page: Int) = rows.countOnPage(resolvedProfile(), containerId, page)

    override suspend fun folderIdsOnPage(containerId: Long, page: Int) =
        rows.folderIdsOnPage(resolvedProfile(), containerId, page)

    override suspend fun deleteByContainers(containerIds: List<Long>) = rows.deleteByContainers(resolvedProfile(), containerIds)

    override suspend fun deleteOnPage(containerId: Long, page: Int) = rows.deleteOnPage(resolvedProfile(), containerId, page)

    override suspend fun updateClassName(id: Long, className: String) = rows.updateClassName(resolvedProfile(), id, className)

    override suspend fun renameFolder(id: Long, name: String) = rows.renameFolder(resolvedProfile(), id, name)

    override suspend fun updateSpans(id: Long, spanX: Int, spanY: Int) = rows.updateSpans(resolvedProfile(), id, spanX, spanY)

    override suspend fun updateWidgetId(id: Long, appWidgetId: Int) = rows.updateWidgetId(resolvedProfile(), id, appWidgetId)

    override suspend fun clearWidgetId(id: Long) = rows.clearWidgetId(resolvedProfile(), id)

    override suspend fun boundWidgetIds() = rows.boundWidgetIds(resolvedProfile())

    override suspend fun getAllOnce() = rows.getAll(resolvedProfile())

    override suspend fun replaceLayout(items: List<HomeItemEntity>) = rows.replaceLayout(resolvedProfile(), items)

    override suspend fun boundWidgetIdsOfEveryProfile() = rows.boundWidgetIdsOfEveryProfile()

    override suspend fun shortcutIdsOfEveryProfile(pkg: String, user: Long) = rows.shortcutIdsOfEveryProfile(pkg, user)

    override fun observeShortcutOwnersOfEveryProfile() = rows.observeShortcutOwnersOfEveryProfile()

    override suspend fun getAllOfEveryProfile() = rows.getAllOfEveryProfile()

    override suspend fun profileIds() = rows.profileIds()

    override suspend fun deleteProfile(profile: Long) = rows.clear(profile)

    override suspend fun replaceEveryProfile(items: List<HomeItemEntity>) = rows.replaceEveryProfile(resolvedProfile(), items)
}
