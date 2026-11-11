package org.arkikeskus.launcher.feature.settings

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.data.IconPackInfo
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

/** The main page, as a screen of [SettingsContent]. */
private const val SCREEN_MAIN = ""

/** The two sub-screens of the App drawer page; the prefix keeps them apart from the category keys. */
private const val SCREEN_HIDDEN_APPS = "sub:hidden"
private const val SCREEN_DRAWER_FOLDERS = "sub:folders"

/**
 * Settings itself, free of the view model: it shows [s] and [lists] and asks for every change through [actions], so
 * a test can show any part of it. [blurAvailability] and [backupScreen] are parameters for the same reason (the
 * device's answer, and a screen with a view model of its own).
 *
 * One screen shows at a time: the main page with the categories, a category's page, or a sub-screen opened from the
 * App drawer page. Which one is saveable state, so it survives the activity being recreated (rotation, a theme
 * switch), also under a system picker: a page that forgot it was open never received the picker's result. Back
 * leaves a sub-screen for its page, a page for the main page, and the main page for wherever Settings was opened.
 */
@Composable
internal fun SettingsContent(
    s: LauncherSettings,
    lists: SettingsLists,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    blurAvailability: BlurAvailability = rememberBlurAvailability(),
    backupScreen: @Composable (onBack: () -> Unit) -> Unit = {
        BackupScreen(onBack = it, modifier = Modifier.fillMaxSize())
    },
) {
    // The open category's key, or null on the main page; a key this version does not know counts as the main page.
    var pageKey by rememberSaveable { mutableStateOf<String?>(null) }
    var subScreen by rememberSaveable { mutableStateOf<String?>(null) }
    // Hoisted above the screens, so a page's place survives a visit to a sub-screen or to another page (a scroll
    // state kept inside a page would leave the composition with it), and saveable, so it survives recreation. A
    // fresh Settings open starts where the main list was left if that was less than five minutes ago
    // (SettingsScrollMemory), else at the top.
    val mainScroll = rememberSaveable(saver = ScrollState.Saver) {
        ScrollState(SettingsScrollMemory.restore(SystemClock.elapsedRealtime()))
    }
    val pageScrolls = SettingsCategory.entries.associateWith { category ->
        key(category.key) { rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) } }
    }
    // Leaving (HOME, Back, another app on top): note the place and the time for the next open.
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { SettingsScrollMemory.remember(mainScroll.value, SystemClock.elapsedRealtime()) }
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
        Surface(modifier = modifier.fillMaxSize(), color = palette.bg) {
            val screen = subScreen ?: SettingsCategory.fromKey(pageKey)?.key ?: SCREEN_MAIN
            AnimatedContent(
                targetState = screen,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                label = "settings screen",
            ) { shown ->
                when (shown) {
                    SCREEN_MAIN -> SettingsMainPage(onOpen = { pageKey = it.key }, scroll = mainScroll)

                    SCREEN_HIDDEN_APPS -> HiddenAppsManager(
                        apps = lists.apps,
                        hiddenKeys = lists.hiddenKeys,
                        onSetHidden = actions::setAppHidden,
                        onBack = { subScreen = null },
                        modifier = Modifier.fillMaxSize(),
                    )

                    SCREEN_DRAWER_FOLDERS -> {
                        val newFolderName = stringResource(R.string.drawer_folder_default)
                        DrawerFoldersManager(
                            folders = lists.drawerFolders,
                            apps = lists.apps,
                            hiddenKeys = lists.hiddenKeys,
                            onCreate = { actions.createDrawerFolder(newFolderName) },
                            onRename = actions::renameDrawerFolder,
                            onDelete = actions::deleteDrawerFolder,
                            onSetMember = actions::setAppInDrawerFolder,
                            onBack = { subScreen = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // The backup category has one thing on it: the backup screen itself.
                    SettingsCategory.BACKUP.key -> backupScreen { pageKey = null }

                    else -> {
                        val category = SettingsCategory.fromKey(shown) ?: return@AnimatedContent
                        SettingsCategoryPage(
                            category = category,
                            s = s,
                            lists = lists,
                            actions = actions,
                            blurAvailability = blurAvailability,
                            onBack = { pageKey = null },
                            onOpenHiddenApps = { subScreen = SCREEN_HIDDEN_APPS },
                            onOpenFolders = { subScreen = SCREEN_DRAWER_FOLDERS },
                            scroll = pageScrolls.getValue(category),
                        )
                    }
                }
            }
        }
    }
}
