package org.arkikeskus.launcher.data.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import org.arkikeskus.launcher.data.HomeFolderIcons
import org.arkikeskus.launcher.data.HomeLayout
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.LauncherSettings

/** What a screen shows of ONE home profile: its settings, its layout rows and its folders' own icons. */
data class HomeProfileContent(
    val settings: LauncherSettings,
    val layout: HomeLayout,
    val folderIcons: Map<String, CustomIcon>,
) {
    val homeProfileId: Long get() = layout.profileId
}

/**
 * The three parts of the profile that is on, as one value. Each part comes from a store of its own and
 * learns of a profile switch at its own moment, so around a switch the latest values are for a while of
 * two profiles: such a combination is never handed on. A switch therefore arrives as ONE change, once all
 * three are of the new profile; a change inside the profile arrives at once.
 */
fun homeProfileContent(
    settings: Flow<LauncherSettings>,
    layout: Flow<HomeLayout>,
    folderIcons: Flow<HomeFolderIcons>,
): Flow<HomeProfileContent> =
    combine(settings, layout, folderIcons) { s, l, i ->
        if (s.homeProfileId == l.profileId && i.homeProfileId == l.profileId) HomeProfileContent(s, l, i.icons) else null
    }.filterNotNull()

/** [homeProfileContent] of the launcher's own stores. */
fun homeProfileContent(settings: SettingsRepository, layout: HomeLayoutRepository): Flow<HomeProfileContent> =
    homeProfileContent(settings.settings, layout.homeLayout, settings.folderIconsOfProfile)
