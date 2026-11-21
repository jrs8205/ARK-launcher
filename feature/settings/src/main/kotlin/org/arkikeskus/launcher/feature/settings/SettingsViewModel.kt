package org.arkikeskus.launcher.feature.settings

import androidx.lifecycle.ViewModel
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.ShownDrawerWallpaper
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.data.IconPackInfo
import org.arkikeskus.launcher.data.IconPackRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.PrivateSpaceRepository
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.HomeProfile
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject
import org.arkikeskus.launcher.model.IconShape

/** Something Settings tells the user once, when what was asked for did not happen. */
internal enum class SettingsNotice { PROFILE_NOT_MADE, PROFILE_NOT_CHANGED, PROFILE_NOT_DELETED, PROFILE_LEFT_EMPTY }

/** The text of [notice]. */
internal fun settingsNoticeText(notice: SettingsNotice): Int = when (notice) {
    SettingsNotice.PROFILE_NOT_MADE -> R.string.settings_profile_create_failed
    SettingsNotice.PROFILE_NOT_CHANGED -> R.string.settings_profile_change_failed
    SettingsNotice.PROFILE_NOT_DELETED -> R.string.settings_profile_delete_failed
    SettingsNotice.PROFILE_LEFT_EMPTY -> R.string.settings_profile_left_empty
}

/**
 * What Settings says after a profile was to be deleted, or null when there is nothing to say (it is gone, or
 * the deletion was refused by rule, which the screen does not offer). The two failures are told apart, since
 * they leave different things: [HomeProfileRepository.Deleted.Failed] a whole profile,
 * [HomeProfileRepository.Deleted.LeftEmpty] a listed profile with nothing on it, which is to be deleted again.
 */
internal fun deletionNotice(result: HomeProfileRepository.Deleted): SettingsNotice? = when (result) {
    HomeProfileRepository.Deleted.Failed -> SettingsNotice.PROFILE_NOT_DELETED
    HomeProfileRepository.Deleted.LeftEmpty -> SettingsNotice.PROFILE_LEFT_EMPTY
    HomeProfileRepository.Deleted.Done, HomeProfileRepository.Deleted.Refused -> null
}

@HiltViewModel
internal class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val iconPackRepository: IconPackRepository,
    private val customIcons: CustomIconStore,
    appRepository: AppRepository,
    private val drawerWallpaper: DrawerWallpaperStore,
    privateSpace: PrivateSpaceRepository,
    private val homeProfileRepository: HomeProfileRepository,
) : ViewModel(), SettingsActions {

    /** The home profiles, in their order: one unnamed profile for a launcher that has made no other. */
    val homeProfiles: StateFlow<List<HomeProfile>> = homeProfileRepository.profiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = listOf(HomeProfile(HomeProfile.FIRST_ID, "")),
    )

    /** What the screen says once, in a toast ([settingsNoticeText]); the buffer keeps one said while nothing listens. */
    private val _notices = Channel<SettingsNotice>(Channel.BUFFERED)
    val notices: Flow<SettingsNotice> = _notices.receiveAsFlow()

    /** A profile that could not be made whole is not made at all, and the screen says so: it would otherwise
     *  just not appear in the list. */
    override fun createHomeProfile(name: String, copyCurrent: Boolean) = update {
        if (homeProfileRepository.create(name, copyCurrent) == HomeProfileRepository.Created.Failed) {
            _notices.trySend(SettingsNotice.PROFILE_NOT_MADE)
        }
    }

    /** Settings is the only place a profile is switched from by hand so far, and it switches at once. A
     *  switch the storage did not take (a full disk) is said, as a profile that could not be made is: the
     *  repository answers with a result and throws nothing, and so nothing escapes this scope. */
    override fun switchHomeProfile(id: Long) = update {
        if (!homeProfileRepository.switchTo(id)) _notices.trySend(SettingsNotice.PROFILE_NOT_CHANGED)
    }

    override fun renameHomeProfile(id: Long, name: String) = update {
        if (!homeProfileRepository.rename(id, name)) _notices.trySend(SettingsNotice.PROFILE_NOT_CHANGED)
    }

    override fun deleteHomeProfile(id: Long) = update {
        deletionNotice(homeProfileRepository.delete(id))?.let { _notices.trySend(it) }
    }

    /** Whether the launcher sees a private space on this phone: the rows that are about one exist only then. */
    val privateSpaceSeen: StateFlow<Boolean> = privateSpace.state.map { it != null }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = privateSpace.state.value != null,
    )

    /** The user's own picture behind the app drawer (a Telegram request after 0.9.9), with the home profile it is
     *  of: the page takes it only while it is the picture of the profile the page is about. */
    val drawerWallpaperPicture: StateFlow<ShownDrawerWallpaper> = drawerWallpaper.shown

    /** Saves the picture at [uri] as the drawer's wallpaper in the home profile [profile], the one the page shows. */
    override fun importDrawerWallpaper(uri: Uri, profile: Long) = update {
        // Not for a profile deleted while the picker was open (HomeProfileRepository.setDrawerPicture).
        homeProfileRepository.setDrawerPicture(profile) { pictures, pick -> pictures.import(uri, pick) }
    }

    override fun clearDrawerWallpaper(profile: Long) = update { drawerWallpaper.clear(profile) }

    /** Null until the stored settings are read: the page shows no value before that (see SettingsWhenRead). */
    val settings: StateFlow<LauncherSettings?> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
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
    override fun setHidePrivateSpaceWhenLocked(value: Boolean) = update { repository.setHidePrivateSpaceWhenLocked(value) }
    override fun setShowFrequentApps(value: Boolean) = update { repository.setShowFrequentApps(value) }
    override fun setDrawerOpensAtTop(value: Boolean) = update { repository.setDrawerOpensAtTop(value) }
    override fun setDrawerSearchAtBottom(value: Boolean) = update { repository.setDrawerSearchAtBottom(value) }
    override fun setCountSystemUsage(value: Boolean) = update { repository.setCountSystemUsage(value) }
    override fun setSortDrawerFolders(value: Boolean) = update { repository.setSortDrawerFolders(value) }
    override fun setMixDrawerFolders(value: Boolean) = update { repository.setMixDrawerFolders(value) }
    override fun setBlurBehindDrawer(value: Boolean) = update { repository.setBlurBehindDrawer(value) }
    override fun setDrawerBlurStrength(value: Float) = update { repository.setDrawerBlurStrength(value) }
    override fun setDrawerBackgroundColor(argb: Int?) = update { repository.setDrawerBackgroundColor(argb) }
    override fun setDrawerLabelColor(argb: Int?) = update { repository.setDrawerLabelColor(argb) }
    override fun setDrawerPictureDim(value: Float) = update { repository.setDrawerPictureDim(value) }
    override fun setFontFamily(font: String) = update { repository.setFontFamily(font) }
    override fun setBaseColour(colour: String) = update { repository.setBaseColour(colour) }
    override fun setThemeAllIcons(value: Boolean) = update { repository.setThemeAllIcons(value) }
    override fun setFolderColumns(value: Int) = update { repository.setFolderColumns(value) }
    // Shrinking the grid can leave shortcuts at an out-of-range cell; they are packed back on-screen before
    // the new (smaller) size reaches the home screen, so nothing draws off-grid. The packing and the new size
    // are one home profile's, the one whose grid the page showed (HomeProfileRepository.setHomeColumns).
    override fun setHomeColumns(value: Int, profile: Long) = update { homeProfileRepository.setHomeColumns(value, profile) }

    override fun setHomeRows(value: Int, profile: Long) = update { homeProfileRepository.setHomeRows(value, profile) }
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
    override fun setWallpaperScroll(value: Boolean) = update { repository.setWallpaperScroll(value) }
    override fun setChargingAnimation(value: Boolean) = update { repository.setChargingAnimation(value) }
    override fun setShowNotificationDots(value: Boolean) = update { repository.setShowNotificationDots(value) }
    override fun setNotificationDotCount(value: Boolean) = update { repository.setNotificationDotCount(value) }
    override fun setNotificationDotScale(value: Float) = update { repository.setNotificationDotScale(value) }
    override fun setUseThemedIcons(value: Boolean) = update { repository.setUseThemedIcons(value) }
    override fun setIconPack(pkg: String) = update { repository.setIconPackPackage(pkg) }
    override fun setIconShape(shape: IconShape) = update { repository.setIconShape(shape) }
    override fun setSearchContacts(value: Boolean) = update { repository.setSearchContacts(value) }
    override fun setSearchHiddenApps(value: Boolean) = update { repository.setSearchHiddenApps(value) }

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
    override fun setWeatherIconSize(value: String) = update { repository.setWeatherIconSize(value) }
    override fun setHomeGlassPanel(value: Boolean) = update { repository.setHomeGlassPanel(value) }
    override fun setHomeGlassOpacity(value: Float) = update { repository.setHomeGlassOpacity(value) }
    override fun setStatusBarScrimOpacity(value: Float) = update { repository.setStatusBarScrimOpacity(value) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
