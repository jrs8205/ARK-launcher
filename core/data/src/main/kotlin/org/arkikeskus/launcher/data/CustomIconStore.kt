package org.arkikeskus.launcher.data

import android.content.Context
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

/** Sets an app's or a folder's picked icon; a gallery image it replaces or clears is deleted. */
@Singleton
class CustomIconStore @Inject constructor(
    private val settings: SettingsRepository,
    private val source: CustomIconSource,
) {
    suspend fun setAppIcon(key: String, icon: CustomIcon?) {
        val old = settings.customIcons.first()[key]
        settings.setCustomIcon(key, icon)
        dropReplaced(old, icon)
    }

    /** [key] is a [FolderIconKey]. */
    suspend fun setFolderIcon(key: String, icon: CustomIcon?) {
        val old = settings.folderIcons.first()[key]
        settings.setFolderIcon(key, icon)
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
        val used = (settings.customIcons.first().values + settings.folderIcons.first().values)
            .filterIsInstance<CustomIcon.FromFile>()
            .mapTo(HashSet()) { it.name }
        source.files.deleteUnused(used, savedBefore)
    }

    private fun dropReplaced(old: CustomIcon?, new: CustomIcon?) {
        if (old is CustomIcon.FromFile && old != new) source.files.delete(old)
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
