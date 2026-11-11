package org.arkikeskus.launcher.feature.settings

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.data.IconPackInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.feature.backup.BackupScreen
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.component.LocalIconPack
import org.arkikeskus.launcher.ui.component.LocalThemeAllIcons
import org.arkikeskus.launcher.ui.component.LocalThemedIcons
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** The launcher's Settings: the view model's state handed to [SettingsContent], which is the page. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val drawerWallpaper by viewModel.drawerWallpaperPicture.collectAsStateWithLifecycle()
    val allApps by viewModel.apps.collectAsStateWithLifecycle()
    val hiddenKeys by viewModel.hiddenKeys.collectAsStateWithLifecycle()
    val drawerFolders by viewModel.drawerFolders.collectAsStateWithLifecycle()
    val iconPacks by viewModel.iconPacks.collectAsStateWithLifecycle()
    SettingsContent(
        s = s,
        lists = SettingsLists(
            apps = allApps,
            hiddenKeys = hiddenKeys,
            drawerFolders = drawerFolders,
            iconPacks = iconPacks,
            drawerWallpaper = drawerWallpaper,
        ),
        actions = viewModel,
        modifier = modifier,
    )
}

/** What the page shows besides the settings themselves: the apps, what is hidden, the drawer's folders (null while
 *  they load), the installed icon packs and the user's own picture behind the drawer. */
internal class SettingsLists(
    val apps: List<AppItem> = emptyList(),
    val hiddenKeys: Set<String> = emptySet(),
    val drawerFolders: List<DrawerFolder>? = emptyList(),
    val iconPacks: List<IconPackInfo> = emptyList(),
    val drawerWallpaper: DrawerWallpaper? = null,
)

/**
 * The Settings page itself, free of the view model: it shows [s] and [lists] and asks for every change through
 * [actions], so a test can show any part of it. [blurAvailability] and [backupScreen] are parameters for the same
 * reason (the device's answer, and a screen with a view model of its own).
 */
@Composable
internal fun SettingsContent(
    s: LauncherSettings,
    lists: SettingsLists,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    blurAvailability: BlurAvailability = rememberBlurAvailability(),
    backupScreen: @Composable (onBack: () -> Unit) -> Unit = {
        BackupScreen(onBack = it, modifier = modifier.fillMaxSize())
    },
) {
    val allApps = lists.apps
    val hiddenKeys = lists.hiddenKeys
    val drawerFolders = lists.drawerFolders
    val context = LocalContext.current
    // Saveable: the activity is recreated on rotation and on a theme switch, and that can happen
    // under the Backup page's file picker — a page that forgot it was open never received the
    // picker's result (an empty file in Downloads, no message).
    var showHiddenManager by rememberSaveable { mutableStateOf(false) }
    var showFolderManager by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    // Hoisted above the subpage early-returns so the main list's scroll position survives a visit to
    // the hidden-apps / backup subpage (an inline rememberScrollState would leave composition with the
    // list and forget the position — the "settings always restart at the top" complaint). Saveable so
    // it also survives activity recreation. A fresh Settings open starts where the list was left if
    // that was less than five minutes ago (SettingsScrollMemory), else at the top.
    val mainScrollState = rememberSaveable(saver = ScrollState.Saver) {
        ScrollState(SettingsScrollMemory.restore(SystemClock.elapsedRealtime()))
    }
    // Leaving (HOME, Back, another app on top): note the place and the time for the next open.
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { SettingsScrollMemory.remember(mainScrollState.value, SystemClock.elapsedRealtime()) }
    }
    val palette = settingsPalette(isSystemInDarkTheme())

    CompositionLocalProvider(
        LocalExpressivePalette provides palette,
        // So AppIcons in the pickers (left-edge app, hidden apps) match the selected icon pack / themed
        // icons like every other surface.
        LocalIconPack provides s.iconPackPackage,
        LocalThemedIcons provides s.useThemedIcons,
        LocalThemeAllIcons provides s.themeAllIcons,
    ) {
        if (showBackup) {
            backupScreen { showBackup = false }
            return@CompositionLocalProvider
        }

        if (showHiddenManager) {
            HiddenAppsManager(
                apps = allApps,
                hiddenKeys = hiddenKeys,
                onSetHidden = actions::setAppHidden,
                onBack = { showHiddenManager = false },
                modifier = modifier.fillMaxSize(),
            )
            return@CompositionLocalProvider
        }

        if (showFolderManager) {
            val newFolderName = stringResource(R.string.drawer_folder_default)
            DrawerFoldersManager(
                folders = drawerFolders,
                apps = allApps,
                hiddenKeys = hiddenKeys,
                onCreate = { actions.createDrawerFolder(newFolderName) },
                onRename = actions::renameDrawerFolder,
                onDelete = actions::deleteDrawerFolder,
                onSetMember = actions::setAppInDrawerFolder,
                onBack = { showFolderManager = false },
                modifier = modifier.fillMaxSize(),
            )
            return@CompositionLocalProvider
        }

        Surface(modifier = modifier.fillMaxSize(), color = palette.bg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(mainScrollState)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    color = palette.text,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp),
                )

                SettingRow("T2") { DefaultLauncherRow() }

                CollapsibleSection(
                    title = stringResource(R.string.settings_gestures),
                    collapsed = "gestures" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("gestures", it) },
                ) {
                    GesturesRows(s, allApps, actions)
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_folder_appearance),
                    collapsed = "folders" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("folders", it) },
                ) {
                    FolderAppearanceRows(s, actions)
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_clock_widget),
                    collapsed = "clock_widget" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("clock_widget", it) },
                ) {
                    ClockWidgetLookRows(s, actions)
                    WeatherIconRows(s, actions)
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_dock),
                    collapsed = "dock" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("dock", it) },
                ) {
                    DockRows(s, actions)
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_drawer),
                    collapsed = "drawer" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("drawer", it) },
                ) {
                    DrawerRows(
                        s = s,
                        lists = lists,
                        actions = actions,
                        blurAvailability = blurAvailability,
                        onOpenHiddenApps = { showHiddenManager = true },
                        onOpenFolders = { showFolderManager = true },
                    )
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_home),
                    collapsed = "home" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("home", it) },
                ) {
                    HomeGridRows(s, actions)
                    SystemBarsHideRows(s, actions)
                    WidgetGeneralRows(s, actions)
                    WeatherToggleRow(s, actions)
                    WeatherDetailRows(s, allApps, actions)
                    NotificationWidgetRows(s, actions)
                    PeopleWidgetRows(s, actions)
                    OwnStatusBarRows(s, actions)
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_icons),
                    collapsed = "icons" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("icons", it) },
                ) {
                    IconsRows(s, lists.iconPacks, actions)
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_notifications),
                    collapsed = "notifications" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("notifications", it) },
                ) {
                    NotificationsRows(s, actions)
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_sounds),
                    collapsed = "sounds" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("sounds", it) },
                ) {
                    LockSoundsSettings(
                        enabled = s.lockSoundsEnabled,
                        lockUri = s.lockSoundUri,
                        unlockUri = s.unlockSoundUri,
                        onSetEnabled = actions::setLockSoundsEnabled,
                        onSetSound = actions::setLockSound,
                    )
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_search),
                    collapsed = "search" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("search", it) },
                ) {
                    ContactsSearchRow(s, actions)
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_permissions),
                    collapsed = "permissions" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("permissions", it) },
                ) {
                    PermissionsRows()
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_backup),
                    collapsed = "backup" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("backup", it) },
                ) {
                    BackupRow(onOpen = { showBackup = true })
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_updates),
                    collapsed = "updates" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("updates", it) },
                ) {
                    UpdatesRow()
                }

                CollapsibleSection(
                    title = stringResource(R.string.settings_feedback),
                    collapsed = "feedback" in s.collapsedSettingsSections,
                    onToggle = { actions.setSectionCollapsed("feedback", it) },
                ) {
                    FeedbackRows(onOpen = { openLink(context, it) })
                }

                // Support the work: a quiet section at the very end (SupportSection.kt), the browser opens the page.
                SettingRow("E1") { SupportSection(onOpen = { openSupportLink(context, it) }) }

                VersionLine()
            }
        }
    }
}
