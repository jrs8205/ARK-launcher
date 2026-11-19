package org.arkikeskus.launcher.data.profile

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.model.HomeProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The home profiles as the user has them: making one, switching, renaming and deleting. A profile is its
 * settings ([HomeProfileStore]), its layout rows, the pictures it shows and the shortcuts it has pinned;
 * this is the one place that keeps them together.
 *
 * Making and deleting a profile are writes to several stores, one after another, with no transaction over
 * them. Once begun they run to their end whatever becomes of the caller ([NonCancellable], as a restore
 * does): Settings asks and can be left the same moment, and a copy cut off after it was listed was the
 * source's look over an empty home screen, a delete cut off after its rows went a profile left in the list
 * with nothing on it.
 */
@Singleton
class HomeProfileRepository @Inject constructor(
    private val store: HomeProfileStore,
    private val layout: HomeLayoutRepository,
    private val settings: SettingsRepository,
    private val resolver: AppRowResolver,
    private val drawerPictures: DrawerWallpaperStore,
) {
    val profiles: Flow<List<HomeProfile>> = store.profiles

    val activeId: Flow<Long> = store.id

    sealed interface Created {
        data class Done(val profile: HomeProfile) : Created

        /** The list is at its limit ([HomeProfile.MAX_PROFILES]). */
        data object Full : Created
    }

    /**
     * A new profile after the others: a copy of the one that is on ([copyCurrent]: its look, its layout, its
     * drawer folders and pictures; an app widget arrives as a placeholder to set up again), or an empty one
     * with the default look. It is not switched to.
     */
    suspend fun create(name: String, copyCurrent: Boolean): Created = withContext(NonCancellable) {
        val source = store.current()
        val profile = store.create(name, copyOf = source.takeIf { copyCurrent }) ?: return@withContext Created.Full
        if (copyCurrent) {
            val folderIds = layout.copyProfile(from = source, to = profile.id)
            // A home folder's own icon is filed by its row id, and the copy's folders are new rows.
            settings.moveHomeFolderIcons(profile.id, folderIds)
            drawerPictures.copy(from = source, to = profile.id)
        }
        Created.Done(profile)
    }

    suspend fun switchTo(id: Long) = store.setActive(id)

    suspend fun rename(id: Long, name: String) = store.rename(id, name)

    /**
     * Deletes [id] with everything it has. False for the last profile and for one that is not there.
     * Deleting the profile that is on switches to the next one in the list first (the one before it for
     * the last in the list).
     */
    suspend fun delete(id: Long): Boolean = withContext(NonCancellable) {
        val list = store.list()
        val index = list.indexOfFirst { it.id == id }
        if (list.size <= 1 || index < 0) return@withContext false
        if (store.current() == id) store.setActive((list.getOrNull(index + 1) ?: list[index - 1]).id)
        // The rows first: from here on the profile shows nothing, and what is left of it can be deleted
        // again. Its widgets' ids are free at the home screen's next sweep; the shortcuts it had pinned
        // are pinned again as the other profiles still have them, under the lock every pin change takes.
        ShortcutPinLock.withLock {
            for ((packageName, userSerial) in layout.deleteProfile(id)) {
                resolver.pinShortcuts(packageName, userSerial, layout.homeShortcutIds(packageName, userSerial))
            }
        }
        drawerPictures.delete(id)
        // Its folders' pictures, if no other profile shows them, go with the unused pictures at the next start.
        store.delete(id)
    }
}
