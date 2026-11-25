package org.arkikeskus.launcher.data

import android.content.Context
import java.io.IOException
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.di.ApplicationScope
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.IconChoices
import org.arkikeskus.launcher.model.IconPackChoice
import javax.inject.Inject
import javax.inject.Singleton

/** Sets an app's, a folder's or a pinned shortcut's picked icon; a gallery image it replaces or clears is deleted. */
@Singleton
class CustomIconStore @Inject constructor(
    private val settings: SettingsRepository,
    private val source: CustomIconSource,
) {
    suspend fun setAppIcon(key: String, icon: CustomIcon?, label: String? = null) {
        val old = settings.setAppAppearance(key, label, icon)
        dropReplaced(old, icon)
    }

    /** [key] is a [FolderIconKey]. */
    suspend fun setFolderIcon(key: String, icon: CustomIcon?) {
        val old = settings.folderIcons.first()[key]
        settings.setFolderIcon(key, icon)
        dropReplaced(old, icon)
    }

    /** [key] is a [ShortcutIconKey] (issue #23). */
    suspend fun setShortcutIcon(key: String, icon: CustomIcon?) {
        val old = settings.shortcutIcons.first()[key]
        settings.setShortcutIcon(key, icon)
        dropReplaced(old, icon)
    }

    /** Deletes a drawer folder with its icon: the next new folder may get the same id. */
    suspend fun deleteDrawerFolder(id: Long) {
        val old = settings.folderIcons.first()[FolderIconKey.drawer(id)]
        settings.deleteDrawerFolder(id)
        dropReplaced(old, null)
    }

    /** Deletes the saved pictures no app or folder uses — left by a process death, a restore, a picker
     *  closed mid-import. Only those saved before [savedBefore] (the launcher's start): one saved since
     *  may still be waiting in an open dialog. */
    suspend fun deleteUnusedPictures(savedBefore: Long) {
        // Nothing goes unless every place that can name a picture was read: "unused" is what none of them
        // names, and a place that could not be read may name any of them. The next start tries again.
        val used = usedPicturesOrNull() ?: return
        source.files.deleteUnused(used, savedBefore)
    }

    /** [usedPictures], or null when it could not be found out whole (the profiles' stores could not be listed). */
    private suspend fun usedPicturesOrNull(): Set<String>? = try { usedPictures() } catch (e: IOException) { null }

    /** The saved pictures some app, shortcut or folder shows: the folders of EVERY home profile, since a
     *  profile that is not showing still uses its pictures. */
    private suspend fun usedPictures(): Set<String> =
        (settings.customIcons.first().values + settings.folderIconsOfEveryProfile() + settings.shortcutIcons.first().values)
            .filterIsInstance<CustomIcon.FromFile>()
            .mapTo(HashSet()) { it.name }

    /** Deletes the picture [old] was, once [new] has taken its place, unless something still shows it: a
     *  home profile made as a copy of another has its folders' pictures in common with it. */
    private suspend fun dropReplaced(old: CustomIcon?, new: CustomIcon?) {
        if (old !is CustomIcon.FromFile || old == new) return
        // Kept when it cannot be told whether something still shows it; the start-up sweep takes it later.
        val used = usedPicturesOrNull() ?: return
        if (old.name !in used) source.files.delete(old)
    }
}

/** [IconChoices] from the installed icon packs and the launcher's saved images. */
@Singleton
class IconChoicesSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iconPacks: IconPackRepository,
    private val source: CustomIconSource,
    @ApplicationScope private val scope: CoroutineScope,
) : IconChoices {
    override fun packs(): List<IconPackChoice> = iconPacks.installedPacks().map { IconPackChoice(it.packageName, it.label) }

    override fun icons(pack: String): List<String> = iconPacks.iconNames(pack)

    override fun suggestion(pack: String, app: AppItem): String? = iconPacks.suggestion(pack, app.componentName)

    override fun importImage(uri: Uri): CustomIcon? = source.files.import(context, uri)

    /** Off the caller's thread and outside its scope: the dialog asking for it is going away. */
    override fun discard(icon: CustomIcon) {
        if (icon is CustomIcon.FromFile) scope.launch { source.files.delete(icon) }
    }
}
