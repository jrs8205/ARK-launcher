package org.arkikeskus.launcher.feature.settings

import androidx.lifecycle.ViewModel
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.DrawerWallpaper
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.IconPackInfo
import org.arkikeskus.launcher.data.IconPackRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject

@HiltViewModel
internal class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
    private val iconPackRepository: IconPackRepository,
    private val customIcons: CustomIconStore,
    appRepository: AppRepository,
    private val drawerWallpaper: DrawerWallpaperStore,
) : ViewModel(), SettingsActions {

    /** The user's own picture behind the app drawer, or null (a Telegram request after 0.9.9). */
    val drawerWallpaperPicture: StateFlow<DrawerWallpaper?> = drawerWallpaper.current

    /** Saves the picture at [uri] as the drawer's wallpaper. */
    override fun importDrawerWallpaper(uri: Uri) = update { drawerWallpaper.import(uri) }

    override fun clearDrawerWallpaper() = update { drawerWallpaper.clear() }

    val settings: StateFlow<LauncherSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LauncherSettings(),
    )

    /** Installed icon packs (queried off-main once), for the picker. */
    val iconPacks: StateFlow<List<IconPackInfo>> =
        flow { emit(iconPackRepository.installedPacks()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All launchable apps (sorted), for the hidden-apps manager list. */
    val apps: StateFlow<List<AppItem>> = appRepository.apps
        .map { list -> list.sortedBy { it.label.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Keys currently hidden from the drawer. */
    val hiddenKeys: StateFlow<Set<String>> = repository.hiddenApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    override fun setAppHidden(key: String, hidden: Boolean) = update { repository.setAppHidden(key, hidden) }

    /** Creates a new empty folder in the app drawer (named [name]); it's filled from the drawer. */
    override fun createDrawerFolder(name: String) = update { repository.createDrawerFolder(name) }

    /** The drawer's folders, for the Folders page; null until the store has answered, so a folder's
     *  page restored after process death is not closed on an empty list it never had. */
    val drawerFolders: StateFlow<List<DrawerFolder>?> = repository.drawerFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override fun renameDrawerFolder(id: Long, name: String) = update { repository.renameDrawerFolder(id, name) }

    /** Deletes the folder and the icon picked for it (see CustomIconStore.deleteDrawerFolder). */
    override fun deleteDrawerFolder(id: Long) = update { customIcons.deleteDrawerFolder(id) }

    override fun setAppInDrawerFolder(id: Long, key: String, member: Boolean) = update {
        if (member) repository.addAppsToDrawerFolder(id, listOf(key)) else repository.removeAppFromDrawerFolder(id, key)
    }

    override fun setSwipeUp(value: Boolean) = update { repository.setSwipeUpForDrawer(value) }
    override fun setSwipeDown(value: Boolean) = update { repository.setSwipeDownForNotifications(value) }
    override fun setFolderSwipeOpensFirstApp(value: Boolean) = update { repository.setFolderSwipeOpensFirstApp(value) }
    override fun setHideHomeAppsFromDrawer(value: Boolean) = update { repository.setHideHomeAppsFromDrawer(value) }
    override fun setWidgetEdgesAlignWithIcons(value: Boolean) = update { repository.setWidgetEdgesAlignWithIcons(value) }
    override fun setDockEnabled(value: Boolean) = update { repository.setDockEnabled(value) }
    override fun setDockColumns(value: Int) = update { repository.setDockColumns(value) }
    override fun setDrawerColumns(value: Int) = update { repository.setDrawerColumns(value) }
    override fun setShowDrawerSearch(value: Boolean) = update { repository.setShowDrawerSearch(value) }
    override fun setShowFrequentApps(value: Boolean) = update { repository.setShowFrequentApps(value) }
    override fun setDrawerOpensAtTop(value: Boolean) = update { repository.setDrawerOpensAtTop(value) }
    override fun setSortDrawerFolders(value: Boolean) = update { repository.setSortDrawerFolders(value) }
    override fun setMixDrawerFolders(value: Boolean) = update { repository.setMixDrawerFolders(value) }
    override fun setBlurBehindDrawer(value: Boolean) = update { repository.setBlurBehindDrawer(value) }
    override fun setDrawerBlurStrength(value: Float) = update { repository.setDrawerBlurStrength(value) }
    override fun setDrawerBackgroundColor(argb: Int?) = update { repository.setDrawerBackgroundColor(argb) }
    override fun setDrawerLabelColor(argb: Int?) = update { repository.setDrawerLabelColor(argb) }
    override fun setDrawerPictureDim(value: Float) = update { repository.setDrawerPictureDim(value) }
    override fun setFontFamily(font: String) = update { repository.setFontFamily(font) }
    override fun setThemeAllIcons(value: Boolean) = update { repository.setThemeAllIcons(value) }
    override fun setFolderColumns(value: Int) = update { repository.setFolderColumns(value) }
    override fun setSectionCollapsed(id: String, collapsed: Boolean) = update { repository.setSettingsSectionCollapsed(id, collapsed) }
    override fun setHomeColumns(value: Int) = update {
        // Shrinking the grid can leave shortcuts at an out-of-range cellX; repack them back on-screen
        // before the new (smaller) column count reaches the home screen, so nothing draws off-grid.
        val s = repository.settings.first()
        if (value < s.homeColumns) homeLayoutRepository.reflow(value, s.homeRows)
        repository.setHomeColumns(value)
    }

    override fun setHomeRows(value: Int) = update {
        // Same off-grid guard as the column stepper, on the vertical axis.
        val s = repository.settings.first()
        if (value < s.homeRows) homeLayoutRepository.reflow(s.homeColumns, value)
        repository.setHomeRows(value)
    }
    override fun setShowDockLabels(value: Boolean) = update { repository.setShowDockLabels(value) }
    override fun setShowHomeLabels(value: Boolean) = update { repository.setShowHomeLabels(value) }
    override fun setShowDrawerLabels(value: Boolean) = update { repository.setShowDrawerLabels(value) }
    override fun setShowHomeFolderNames(value: Boolean) = update { repository.setShowHomeFolderNames(value) }
    override fun setShowDrawerFolderNames(value: Boolean) = update { repository.setShowDrawerFolderNames(value) }
    override fun setDockBackgroundOpacity(value: Float) = update { repository.setDockBackgroundOpacity(value) }
    override fun setDockBottomSpacing(value: Int) = update { repository.setDockBottomSpacing(value) }
    override fun setDrawerBackgroundOpacity(value: Float) = update { repository.setDrawerBackgroundOpacity(value) }
    override fun setShowPageIndicator(value: Boolean) = update { repository.setShowPageIndicator(value) }
    override fun setHomePageMarker(value: String) = update { repository.setHomePageMarker(value) }
    override fun setInfiniteScroll(value: Boolean) = update { repository.setInfiniteScroll(value) }
    override fun setShowNotificationDots(value: Boolean) = update { repository.setShowNotificationDots(value) }
    override fun setNotificationDotCount(value: Boolean) = update { repository.setNotificationDotCount(value) }
    override fun setNotificationDotScale(value: Float) = update { repository.setNotificationDotScale(value) }
    override fun setUseThemedIcons(value: Boolean) = update { repository.setUseThemedIcons(value) }
    override fun setIconPack(pkg: String) = update { repository.setIconPackPackage(pkg) }
    override fun setSearchContacts(value: Boolean) = update { repository.setSearchContacts(value) }

    /** Sets (or clears, for null) the app launched by the left-edge home swipe. */
    override fun setLeftSwipeAppKey(key: String?) = update { repository.setLeftSwipeAppKey(key) }

    /** Sets (or clears, for null) the app a tap on the smartspace weather opens. */
    override fun setWeatherAppKey(key: String?) = update { repository.setWeatherAppKey(key) }

    /** How fast the home pager settles on a page (normal / fast / fastest). */
    override fun setPageSwitchSpeed(speed: String) = update { repository.setPageSwitchSpeed(speed) }

    override fun setDesktopLocked(value: Boolean) = update { repository.setDesktopLocked(value) }
    override fun setDoubleTapToLock(value: Boolean) = update { repository.setDoubleTapToLock(value) }
    override fun setLockSoundsEnabled(value: Boolean) = update { repository.setLockSoundsEnabled(value) }
    override fun setLockSound(slot: SoundSlot, uri: String) = update {
        if (slot == SoundSlot.Lock) repository.setLockSoundUri(uri) else repository.setUnlockSoundUri(uri)
    }
    override fun setLongPressScale(value: Float) = update { repository.setLongPressScale(value) }
    override fun setIconSwipeDistance(value: Float) = update { repository.setIconSwipeDistance(value) }

    override fun setAppLabelTextScale(value: Float) = update { repository.setAppLabelTextScale(value) }
    override fun setIconScale(value: Float) = update { repository.setIconScale(value) }
    override fun setDrawerIconScale(value: Float) = update { repository.setDrawerIconScale(value) }
    override fun setAppLabelColor(argb: Int) = update { repository.setAppLabelColor(argb) }
    override fun setTwoLineHomeLabels(value: Boolean) = update { repository.setTwoLineHomeLabels(value) }
    override fun setTwoLineDrawerLabels(value: Boolean) = update { repository.setTwoLineDrawerLabels(value) }
    override fun setShowStatusBar(value: Boolean) = update { repository.setShowStatusBar(value) }
    override fun setShowWeather(value: Boolean) = update { repository.setShowWeather(value) }
    override fun setNetworkPlaceNames(value: Boolean) = update { repository.setNetworkPlaceNames(value) }
    override fun setWidgetTonalBackground(value: Boolean) = update { repository.setWidgetTonalBackground(value) }
    override fun setNotificationWidgetCountStyle(value: String) = update { repository.setNotificationWidgetCountStyle(value) }
    override fun setPeoplePrivacy(value: String) = update { repository.setPeoplePrivacy(value) }
    override fun setPeopleShowApps(value: Boolean) = update { repository.setPeopleShowApps(value) }
    override fun setPeopleBatchEnabled(value: Boolean) = update { repository.setPeopleBatchEnabled(value) }
    override fun setPeopleBatchTimes(value: String) = update { repository.setPeopleBatchTimes(value) }
    override fun setHideSystemStatusBar(value: Boolean) = update { repository.setHideSystemStatusBar(value) }
    override fun setHideSystemNavigationBar(value: Boolean) = update { repository.setHideSystemNavigationBar(value) }
    override fun setFolderShowLabels(value: Boolean) = update { repository.setFolderShowLabels(value) }
    override fun setFolderIconScale(value: Float) = update { repository.setFolderIconScale(value) }
    override fun setFolderSpacing(value: Float) = update { repository.setFolderSpacing(value) }
    override fun setFolderBackgroundColor(argb: Int?) = update { repository.setFolderBackgroundColor(argb) }
    override fun setFolderBackgroundOpacity(value: Float) = update { repository.setFolderBackgroundOpacity(value) }
    override fun setSmartspaceBackgroundColor(argb: Int?) = update { repository.setSmartspaceBackgroundColor(argb) }
    override fun setSmartspaceBackgroundOpacity(value: Float) = update { repository.setSmartspaceBackgroundOpacity(value) }
    override fun setSmartspaceTextScale(value: Float) = update { repository.setSmartspaceTextScale(value) }
    override fun setWeatherIconStyle(value: String) = update { repository.setWeatherIconStyle(value) }
    override fun setWeatherIconLarge(value: Boolean) = update { repository.setWeatherIconLarge(value) }
    override fun setHomeGlassPanel(value: Boolean) = update { repository.setHomeGlassPanel(value) }
    override fun setHomeGlassOpacity(value: Float) = update { repository.setHomeGlassOpacity(value) }
    override fun setStatusBarScrimOpacity(value: Float) = update { repository.setStatusBarScrimOpacity(value) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
