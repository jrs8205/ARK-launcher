package org.arkikeskus.launcher.data.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.DeletedProfileRows
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.ShortcutIconKey
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

    /**
     * The ids something is kept under though the list does not name them: a home screen's rows, a profile's
     * own values, its drawer picture. After a damaged settings file the list is one profile again and the
     * others' things are still there; a copy or a restore that died leaves such things too. Nothing shows
     * them and nothing takes their ids ([create]); a restore removes them all, so its question says so.
     *
     * Read again whenever the rows or the list change. What cannot be listed right now is left out here:
     * the restore reads the same for itself before it writes, and refuses when the two do not agree.
     */
    val unlistedHomeScreens: Flow<Set<Long>> =
        combine(layout.profilesWithRowsNow, store.profiles) { withRows, listed ->
            val stores = try { store.unlistedStores() } catch (e: IOException) { emptySet() }
            val pictures = try { drawerPictures.profilesWithPictures() } catch (e: IOException) { emptySet() }
            withRows + stores + pictures - listed.mapTo(HashSet()) { it.id }
        }.distinctUntilChanged()

    sealed interface Created {
        data class Done(val profile: HomeProfile) : Created

        /** The list is at its limit ([HomeProfile.MAX_PROFILES]). */
        data object Full : Created

        /** The profile could not be made whole (its rows or its picture could not be written): there is no
         *  new profile, and what had been made of it is gone. */
        data object Failed : Created
    }

    /**
     * Runs [work] while the profile that is on stays on: a switch asked for meanwhile waits. For work of
     * several steps about the profile that is on, such as a smaller grid with the rows it has to move.
     */
    suspend fun <T> whileProfileStays(work: suspend () -> T): T = store.whileActiveStays { work() }

    /**
     * A new profile after the others: a copy of the profile [copyOf] (its look, its layout, its drawer folders
     * and pictures; an app widget arrives as a placeholder to set up again), or, for null, an empty one with
     * the default look. [copyOf] is the profile the user saw named as the one to copy when confirming, handed
     * over by the screen: not whichever profile is on when this gets its turn, which after a switch that was
     * waiting is another one (a copy of the wrong profile was then made and reported as done). A source that
     * is gone by then makes nothing ([Created.Failed]). It is not switched to, and cannot be until it is whole: a tap on it the moment
     * it was listed showed it before its rows and its drawer picture were there.
     *
     * It is LISTED LAST, when its values, its rows and its picture are all there. Listed first, a process that
     * died in between left a profile with its source's look over an empty home screen, and so did a row or a
     * picture that could not be written. Now either leaves no profile: on a failure what was made of it is
     * taken away again ([Created.Failed]); after a death it stays unlisted under an id that the next profile
     * is given, and that one starts by clearing it. (Nothing sweeps unlisted rows at a start: the list is
     * read from a file that reads as empty when it is damaged, and such a sweep would then delete every
     * profile's home screen but the first.)
     *
     * For the same reason the id is never one that something is kept under, unless its own store says it was
     * an attempt that died ([HomeProfileStore.prepare]): after a damaged settings file the list is one profile
     * again, and the other profiles' rows and pictures are still there under their ids. What is cleared below
     * is therefore only ever an attempt's left-overs.
     */
    suspend fun create(name: String, copyOf: Long?): Created = withContext(NonCancellable) {
        store.whileActiveStays {
            // Its own values are the first thing written, and that write fails as any other can (a full disk):
            // nothing is made of the profile then, and the store leaves nothing of the attempt.
            val profile = try {
                if (copyOf != null && store.list().none { it.id == copyOf }) return@whileActiveStays Created.Failed
                val kept = layout.profilesWithRows() + drawerPictures.profilesWithPictures()
                store.prepare(name, copyOf, kept) ?: return@whileActiveStays Created.Full
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@whileActiveStays Created.Failed
            }
            try {
                // What an attempt that died left under this id (an id is given again until it is listed). A
                // picture of it that cannot be removed would be the new profile's: no profile is made over it.
                dropRows(profile.id)
                check(drawerPictures.delete(profile.id)) { "what an earlier attempt left could not be cleared" }
                if (copyOf != null) {
                    val folderIds = layout.copyProfile(from = copyOf, to = profile.id)
                    // A home folder's own icon is filed by its row id, and the copy's folders are new rows.
                    settings.moveHomeFolderIcons(profile.id, folderIds)
                    check(drawerPictures.copy(from = copyOf, to = profile.id)) { "the drawer's picture could not be copied" }
                }
                check(store.publish(profile)) { "the list of profiles filled up" }
                Created.Done(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not listed, so not a profile: what was made of it goes, as far as it can be reached.
                runCatching { dropRows(profile.id) }
                runCatching { drawerPictures.delete(profile.id) }
                runCatching { store.discard(profile) }
                Created.Failed
            }
        }
    }

    /**
     * Deletes the rows of the home profile [id]; the shortcuts they had pinned are pinned again as the other
     * profiles still have them, under the lock every pin change takes and off the caller's thread (these are
     * calls into the system, and Settings asks from the main thread). For a caller that holds the profile.
     *
     * The rows go in one transaction, and what they held is returned whatever becomes of the pinning after it:
     * the sets are read one app at a time once the rows have gone, and a reading that failed there used to
     * leave the caller without an answer. A deletion then said the profile was whole while its home screen
     * was empty, and did not let go of its widgets. An app's set that cannot be read or pinned now is left as
     * the system has it: at worst it keeps a pin no home screen shows, until that app's pins next change.
     *
     * The icon the user picked for a pinned shortcut is one value for every profile, filed by the shortcut: it
     * goes with the shortcut's LAST copy, here as when one shortcut is removed from a home screen. Left behind,
     * the same shortcut pinned again later wore the old icon, and a gallery picture stayed in use for ever.
     */
    private suspend fun dropRows(id: Long): DeletedProfileRows = ShortcutPinLock.withLock {
        val gone = layout.deleteProfile(id)
        withContext(Dispatchers.IO) {
            for ((packageName, userSerial) in gone.shortcutOwners) {
                try {
                    val left = layout.homeShortcutIds(packageName, userSerial)
                    resolver.pinShortcuts(packageName, userSerial, left)
                    for ((pkg, shortcutId, serial) in gone.shortcuts) {
                        if (pkg != packageName || serial != userSerial || shortcutId in left) continue
                        val key = ShortcutIconKey.of(pkg, shortcutId, serial)
                        if (key in settings.shortcutIcons.first()) settings.setShortcutIcon(key, null)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Left as the system has it; the other apps' sets are still pinned.
                }
            }
        }
        gone
    }

    /** Switches to [id]. False when the storage did not take the change (a full disk): the profile that was
     *  on is still on. Said as a result, not thrown: Settings asks without catching, and an exception there
     *  took the launcher down. */
    suspend fun switchTo(id: Long): Boolean = written { store.setActive(id) }

    /** Whether [write], a change to the stored profiles, was taken by the storage. */
    private suspend fun written(write: suspend () -> Unit): Boolean = try {
        write()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Saves a picture behind the app drawer for the home profile [profileId] with [save], unless that profile
     * is gone: false then, with nothing kept. The picture is picked in the system's picker, and the profile it
     * was picked for can be deleted before the picker answers, or while the picture is read and saved. A
     * picture saved for a profile that is not in the list could never be removed again, so one that was saved
     * across the deletion is taken away with its folder.
     *
     * [save] is handed the pick's place among the user's picks and removals of that picture
     * ([DrawerWallpaperStore.Pick]), taken HERE, before anything waits: the list is read first, and a removal
     * the user asks for while that read waits is the later action. Taken only at the save, the pick came after
     * the removal, and the picture the user had just removed showed.
     *
     * The profile is not held while the picture is read and saved: reading a picture from another app can take
     * as long as it likes. The LAST look at the list is made under the hold a deletion keeps from its first
     * step to its last ([delete]): a deletion clears the picture folder and only then takes the profile out of
     * the list, and a look between the two found the profile listed and kept a picture in the folder that had
     * just been cleared. Now the look is before the deletion (which then clears what was saved) or after it
     * (and clears it here).
     */
    suspend fun setDrawerPicture(
        profileId: Long,
        save: suspend (DrawerWallpaperStore, DrawerWallpaperStore.Pick) -> Boolean,
    ): Boolean {
        val pick = drawerPictures.pick(profileId)
        if (store.list().none { it.id == profileId }) return false
        var kept = false
        try {
            kept = save(drawerPictures, pick)
        } finally {
            // Once the save has begun, the last look is made whatever becomes of the caller. Settings asks and
            // can be left the same moment: its coroutine is then cancelled while it waits for a deletion's
            // hold, or right after the picture was written, and without this look the picture stayed in the
            // folder of a profile that was gone. Reading and saving the picture itself can still be cancelled.
            kept = withContext(NonCancellable) {
                store.whileActiveStays {
                    if (store.list().any { it.id == profileId }) {
                        kept
                    } else {
                        drawerPictures.delete(profileId)
                        false
                    }
                }
            }
        }
        return kept
    }

    /**
     * The number of columns of the home grid of the home profile [profileId]: the one whose grid the Settings
     * page showed when the user changed it. A smaller grid can leave icons past its edge, so the rows are
     * packed into it before the new size is stored. Both are one profile's: with a tap on another profile
     * between them, one profile's icons moved and the other's grid shrank over icons nobody moved.
     *
     * False, with nothing changed, when [profileId] is not the profile that is on once the hold is had. The
     * hold only keeps a switch from happening in the middle: a change asked for one profile's grid can wait
     * behind a switch, and it then packed the icons of the profile that came on, and could shrink its widgets,
     * for a size the user had chosen while looking at the other one.
     */
    suspend fun setHomeColumns(value: Int, profileId: Long): Boolean = store.whileActiveStays { active ->
        if (active != profileId) return@whileActiveStays false
        val now = settings.settings.first()
        if (value < now.homeColumns) layout.reflow(value, now.homeRows)
        settings.setHomeColumns(value)
        true
    }

    /** As [setHomeColumns], for the rows. */
    suspend fun setHomeRows(value: Int, profileId: Long): Boolean = store.whileActiveStays { active ->
        if (active != profileId) return@whileActiveStays false
        val now = settings.settings.first()
        if (value < now.homeRows) layout.reflow(now.homeColumns, value)
        settings.setHomeRows(value)
        true
    }

    /** Names [id] anew. False when the storage did not take the change: the name is as it was ([switchTo]). */
    suspend fun rename(id: Long, name: String): Boolean = written { store.rename(id, name) }

    /** How a deletion went ([delete]). */
    enum class Deleted {
        /** The profile is gone with everything it had. */
        Done,

        /** Not deleted, by rule: the last profile, or one that is not there. Nothing was changed. */
        Refused,

        /** The storage failed before anything of the profile went: it is whole and still listed. */
        Failed,

        /** Its home screen is gone, but its picture could not be removed or the list could not be written: the
         *  profile is still listed, with nothing on it. Deleting it again finishes the work. */
        LeftEmpty,
    }

    /**
     * Deletes [id] with everything it has ([Deleted.Done]); [Deleted.Refused] for the last profile and for
     * one that is not there. Deleting the profile that is on switches to the next one in the list first (the
     * one before it for the last in the list). No profile can be switched to while this runs: a tap on the
     * profile being deleted, after its rows had gone, left it listed, on, and with an empty home screen.
     *
     * A step the storage does not take (a full disk) is said as a result, not thrown: [Deleted.Failed] when
     * nothing of the profile had gone yet, [Deleted.LeftEmpty] when its rows had and its picture could not be
     * removed, or the list could not be written, after them. Settings asks without catching, and tells the
     * user which of the two it is.
     */
    suspend fun delete(id: Long): Deleted = withContext(NonCancellable) {
        store.whileActiveStays { active ->
            var gone: DeletedProfileRows? = null
            val result = try {
                val list = store.list()
                val index = list.indexOfFirst { it.id == id }
                if (list.size <= 1 || index < 0) return@whileActiveStays Deleted.Refused
                if (active == id) store.setActiveLocked((list.getOrNull(index + 1) ?: list[index - 1]).id)
                // The rows first: from here on the profile shows nothing, and what is left of it can be deleted
                // again ([dropRows] pins its shortcuts again as the other profiles still have them).
                gone = dropRows(id)
                // Its picture next, and whether it went: one that is still on the disk is still the
                // profile's, so the profile stays listed and is deleted again ([Deleted.LeftEmpty]).
                check(drawerPictures.delete(id)) { "the drawer's picture could not be removed" }
                // Its folders' pictures, if no other profile shows them, go with the unused pictures at the next start.
                if (store.delete(id)) Deleted.Done else Deleted.LeftEmpty
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gone == null) Deleted.Failed else Deleted.LeftEmpty
            }
            // Its widgets, whenever its rows went: no home screen shows them, so none would free their ids
            // before the next start, and their providers would go on updating them. An id a row of another
            // profile holds stays. Off the caller's thread, as the pins above: each id is a call into the
            // system, and Settings asks from the main thread.
            gone?.let { rows ->
                runCatching {
                    val held = layout.boundWidgetIds()
                    withContext(Dispatchers.IO) { rows.widgetIds.filter { it !in held }.forEach(widgetIds::release) }
                }
            }
            result
        }
    }
}
