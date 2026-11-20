package org.arkikeskus.launcher.data.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.data.WidgetIdReleaser
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
    private val widgetIds: WidgetIdReleaser,
) {
    val profiles: Flow<List<HomeProfile>> = store.profiles

    val activeId: Flow<Long> = store.id

    sealed interface Created {
        data class Done(val profile: HomeProfile) : Created

        /** The list is at its limit ([HomeProfile.MAX_PROFILES]). */
        data object Full : Created
    }

    /**
     * Runs [work] while the profile that is on stays on: a switch asked for meanwhile waits. For work of
     * several steps about the profile that is on, such as a smaller grid with the rows it has to move.
     */
    suspend fun <T> whileProfileStays(work: suspend () -> T): T = store.whileActiveStays { work() }

    /**
     * A new profile after the others: a copy of the one that is on ([copyCurrent]: its look, its layout, its
     * drawer folders and pictures; an app widget arrives as a placeholder to set up again), or an empty one
     * with the default look. It is not switched to, and cannot be until it is whole: a tap on it the moment
     * it was listed showed it before its rows and its drawer picture were there.
     */
    suspend fun create(name: String, copyCurrent: Boolean): Created = withContext(NonCancellable) {
        store.whileActiveStays { source ->
            val profile = store.create(name, copyOf = source.takeIf { copyCurrent }) ?: return@whileActiveStays Created.Full
            if (copyCurrent) {
                val folderIds = layout.copyProfile(from = source, to = profile.id)
                // A home folder's own icon is filed by its row id, and the copy's folders are new rows.
                settings.moveHomeFolderIcons(profile.id, folderIds)
                drawerPictures.copy(from = source, to = profile.id)
            }
            Created.Done(profile)
        }
    }

    suspend fun switchTo(id: Long) = store.setActive(id)

    /**
     * Saves a picture behind the app drawer for the home profile [profileId] with [save] (which names the
     * same profile to the store), unless that profile is gone: false then, with nothing kept. The picture is
     * picked in the system's picker, and the profile it was picked for can be deleted before the picker
     * answers, or while the picture is read and saved. A picture saved for a profile that is not in the list
     * could never be removed again, so one that was saved across the deletion is taken away with its folder.
     * The profile is not held meanwhile: reading a picture from another app can take as long as it likes.
     */
    suspend fun setDrawerPicture(profileId: Long, save: suspend (DrawerWallpaperStore) -> Boolean): Boolean {
        if (store.list().none { it.id == profileId }) return false
        val saved = save(drawerPictures)
        if (store.list().none { it.id == profileId }) {
            drawerPictures.delete(profileId)
            return false
        }
        return saved
    }

    /**
     * The number of columns of the home grid of the profile that is on. A smaller grid can leave icons past
     * its edge, so the rows are packed into it before the new size is stored. Both are one profile's: with a
     * tap on another profile between them, one profile's icons moved and the other's grid shrank over icons
     * nobody moved.
     */
    suspend fun setHomeColumns(value: Int) = store.whileActiveStays {
        val now = settings.settings.first()
        if (value < now.homeColumns) layout.reflow(value, now.homeRows)
        settings.setHomeColumns(value)
    }

    /** As [setHomeColumns], for the rows. */
    suspend fun setHomeRows(value: Int) = store.whileActiveStays {
        val now = settings.settings.first()
        if (value < now.homeRows) layout.reflow(now.homeColumns, value)
        settings.setHomeRows(value)
    }

    suspend fun rename(id: Long, name: String) = store.rename(id, name)

    /**
     * Deletes [id] with everything it has. False for the last profile and for one that is not there.
     * Deleting the profile that is on switches to the next one in the list first (the one before it for
     * the last in the list). No profile can be switched to while this runs: a tap on the profile being
     * deleted, after its rows had gone, left it listed, on, and with an empty home screen.
     */
    suspend fun delete(id: Long): Boolean = withContext(NonCancellable) {
        store.whileActiveStays { active ->
            val list = store.list()
            val index = list.indexOfFirst { it.id == id }
            if (list.size <= 1 || index < 0) return@whileActiveStays false
            if (active == id) store.setActiveLocked((list.getOrNull(index + 1) ?: list[index - 1]).id)
            // The rows first: from here on the profile shows nothing, and what is left of it can be deleted
            // again. The shortcuts it had pinned are pinned again as the other profiles still have them,
            // under the lock every pin change takes and off the caller's thread: these are calls into the
            // system, and Settings asks from the main thread.
            val gone = ShortcutPinLock.withLock {
                val gone = layout.deleteProfile(id)
                withContext(Dispatchers.IO) {
                    for ((packageName, userSerial) in gone.shortcutOwners) {
                        resolver.pinShortcuts(packageName, userSerial, layout.homeShortcutIds(packageName, userSerial))
                    }
                }
                gone
            }
            drawerPictures.delete(id)
            // Its folders' pictures, if no other profile shows them, go with the unused pictures at the next start.
            val deleted = store.delete(id)
            // Its widgets: no home screen shows them, so none would free their ids before the next start, and
            // their providers would go on updating them. An id a row of another profile holds stays. Off the
            // caller's thread, as the pins above: each id is a call into the system, and Settings asks from
            // the main thread.
            val held = layout.boundWidgetIds()
            withContext(Dispatchers.IO) { gone.widgetIds.filter { it !in held }.forEach(widgetIds::release) }
            deleted
        }
    }
}
