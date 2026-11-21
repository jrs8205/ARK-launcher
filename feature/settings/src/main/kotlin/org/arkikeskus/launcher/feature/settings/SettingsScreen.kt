package org.arkikeskus.launcher.feature.settings

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.data.IconPackInfo
import org.arkikeskus.launcher.feature.backup.BackupScreen
import org.arkikeskus.launcher.model.HomeProfile
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.component.LocalIconPack
import org.arkikeskus.launcher.ui.component.LocalIconShape
import org.arkikeskus.launcher.ui.component.LocalThemeAllIcons
import org.arkikeskus.launcher.ui.component.LocalThemedIcons
import org.arkikeskus.launcher.designsystem.theme.currentBasePalette
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.ui.expressive.systemAnimationsOn

/** The key of the Screensaver page, for [SettingsScreen]'s openPage. */
const val SETTINGS_PAGE_SCREENSAVER = "screensaver"

/**
 * The launcher's Settings: the view model's state handed to [SettingsContent], which is the page.
 * [openPage] is the key of the page to open on, for a start that asks for one; null opens where
 * Settings was left a moment ago, or on the main page.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, openPage: String? = null) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val drawerWallpaper by viewModel.drawerWallpaperPicture.collectAsStateWithLifecycle()
    val allApps by viewModel.apps.collectAsStateWithLifecycle()
    val hiddenKeys by viewModel.hiddenKeys.collectAsStateWithLifecycle()
    val drawerFolders by viewModel.drawerFoldersOfProfile.collectAsStateWithLifecycle()
    val iconPacks by viewModel.iconPacks.collectAsStateWithLifecycle()
    val privateSpaceSeen by viewModel.privateSpaceSeen.collectAsStateWithLifecycle()
    val homeProfiles by viewModel.homeProfiles.collectAsStateWithLifecycle()
    // Told after each composition: what the user changes on this page is changed for the profile it shows.
    val shownProfile = s?.homeProfileId
    androidx.compose.runtime.SideEffect { viewModel.pageShows(shownProfile) }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            android.widget.Toast.makeText(context, settingsNoticeText(notice), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    SettingsWhenRead(
        s = s,
        lists = SettingsLists(
            apps = allApps,
            hiddenKeys = hiddenKeys,
            // The folders of the profile the page is about, or none yet: never another profile's.
            drawerFolders = drawerFoldersShown(drawerFolders, s?.homeProfileId),
            iconPacks = iconPacks,
            // The picture of the profile the page is about, or none: never another profile's ([ShownDrawerWallpaper]).
            drawerWallpaper = s?.let { drawerWallpaper.of(it.homeProfileId) },
            privateSpaceSeen = privateSpaceSeen,
            homeProfiles = homeProfiles,
        ),
        actions = viewModel,
        modifier = modifier,
        openPage = openPage,
    )
}

/**
 * [SettingsContent] once the stored settings are read ([s] is null until then). Meanwhile only the page's own
 * colour shows: a page drawn from the defaults would name the default base colour, and show every value at its
 * default, for the first frames of a cold start.
 */
@Composable
internal fun SettingsWhenRead(
    s: LauncherSettings?,
    lists: SettingsLists,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    openPage: String? = null,
) {
    if (s == null) {
        Surface(modifier = modifier.fillMaxSize(), color = settingsPalette(currentBasePalette()).bg) {}
        return
    }
    SettingsContent(s = s, lists = lists, actions = actions, modifier = modifier, openPage = openPage)
}

/** The drawer's folders as they were read, with the home profile they are of. */
data class FoldersOfProfile(val profile: Long, val folders: List<DrawerFolder>)

/**
 * The folders the Folders page shows: those of [read] while they are of [profile], the profile whose values the
 * page shows; null (as while they load) otherwise.
 *
 * The settings and the folders are read separately, and after a profile switch one arrives before the other.
 * With the new profile's settings and still the old one's folders, the page offered the old profile's folder;
 * opened then, its page stayed open when the new profile had a folder of the same id (a copy has), and the
 * name typed for the one was written to the other: the page's writes go to the profile it shows.
 */
internal fun drawerFoldersShown(read: FoldersOfProfile?, profile: Long?): List<DrawerFolder>? =
    read?.takeIf { it.profile == profile }?.folders

/** What the page shows besides the settings themselves: the apps, what is hidden, the drawer's folders (null while
 *  they load), the installed icon packs, the user's own picture behind the drawer, whether the launcher sees
 *  a private space on this phone (some rows exist only then), and the home profiles in their order (which one is
 *  in use the settings say themselves, `LauncherSettings.homeProfileId`). */
internal class SettingsLists(
    val apps: List<AppItem> = emptyList(),
    val hiddenKeys: Set<String> = emptySet(),
    val drawerFolders: List<DrawerFolder>? = emptyList(),
    val iconPacks: List<IconPackInfo> = emptyList(),
    val drawerWallpaper: DrawerWallpaper? = null,
    val privateSpaceSeen: Boolean = false,
    val homeProfiles: List<HomeProfile> = listOf(HomeProfile(HomeProfile.FIRST_ID, "")),
)

/** The main page, as a screen of [SettingsContent]. */
private const val SCREEN_MAIN = ""

/** How far a page slides as it comes in, and the spring it comes in on. */
private val PAGE_SLIDE = 24.dp
private val PageSlideSpec = spring<IntOffset>(dampingRatio = 0.8f, stiffness = 380f)
private val PageFadeSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)

/** The two sub-screens of the App drawer page; the prefix keeps them apart from the category keys. */
private const val SCREEN_HIDDEN_APPS = "sub:hidden"
private const val SCREEN_DRAWER_FOLDERS = "sub:folders"

/** How deep a screen is: the main page, a category's page, a sub-screen opened from a page. */
internal fun screenDepth(screen: String): Int = when {
    screen == SCREEN_MAIN -> 0
    screen == SCREEN_HIDDEN_APPS || screen == SCREEN_DRAWER_FOLDERS -> 2
    else -> 1
}

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
    openPage: String? = null,
    backupScreen: @Composable (onBack: () -> Unit) -> Unit = {
        BackupScreen(onBack = it, modifier = Modifier.fillMaxSize())
    },
) {
    // Where Settings was left, if that was less than five minutes ago (SettingsScrollMemory): the page and its place.
    // A start that names its page (the screensaver's gear) opens there instead, at the top.
    val start = remember {
        if (openPage != null) SettingsPlace(openPage, 0) else SettingsScrollMemory.restore(SystemClock.elapsedRealtime())
    }
    val startPage = SettingsCategory.fromKey(start.page)
    // The open category's key, or null on the main page; a key this version does not know counts as the main page.
    var pageKey by rememberSaveable { mutableStateOf(startPage?.key) }
    var subScreen by rememberSaveable { mutableStateOf<String?>(null) }
    // Hoisted above the screens, so a page's place survives a visit to a sub-screen or to another page (a scroll
    // state kept inside a page would leave the composition with it), and saveable, so it survives recreation.
    val mainScroll = rememberSaveable(saver = ScrollState.Saver) {
        ScrollState(if (start.page == null) start.offset else 0)
    }
    val pageScrolls = SettingsCategory.entries.associateWith { category ->
        key(category.key) {
            rememberSaveable(saver = ScrollState.Saver) { ScrollState(if (category == startPage) start.offset else 0) }
        }
    }
    // Leaving (HOME, Back, another app on top): note the page, its place and the time for the next open.
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            val page = SettingsCategory.fromKey(pageKey)
            val offset = if (page == null) mainScroll.value else pageScrolls.getValue(page).value
            SettingsScrollMemory.remember(page?.key, offset, SystemClock.elapsedRealtime())
        }
    }
    // What is typed in the main page's search field. Kept while a page opened from it is visited, so Back returns
    // to what was found; not kept over a fresh open of Settings.
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = rememberSearchableSettings(lists.privateSpaceSeen)
    val hits = remember(query, searchable) { searchSettings(query, searchable) }
    // The row a search result asked for. Once its page is composed and its rows have noted themselves, the jump is
    // decided by what is really on screen: that row, or the one it depends on when it is not shown.
    val jumps = remember { SettingJumps() }
    var pendingJump by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingJump, pageKey, query) {
        val asked = pendingJump ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        jumps.target = jumps.resolve(asked)
        pendingJump = null
    }
    // The profile the pages are composed for: the one whose values are shown, as of the last moment Settings
    // was in front. While something of the system's is in front of it (a picker a page opened, a permission
    // question) the pages stay as they are, so that its answer still finds the page that asked: a picture is
    // picked for the profile its picker was opened on, and the page keeps that. Back in front, the pages are
    // the shown profile's. Not a state: it must be the same composition that draws the new values.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val composedFor = remember { longArrayOf(s.homeProfileId) }
    if (lifecycleState == Lifecycle.State.RESUMED) composedFor[0] = s.homeProfileId
    val pagesOf = composedFor[0]
    // Asked for on a page, kept here: above the pages' key, where a profile switch does not start it anew and a
    // recreation restores it ([rememberDrawerPicturePicker]).
    val picturePicker = rememberDrawerPicturePicker(actions::importDrawerWallpaper)
    val palette = settingsPalette(currentBasePalette())
    // With two or more home profiles Settings says which one it is editing, and marks the rows that are its own.
    val severalProfiles = lists.homeProfiles.size >= 2
    val editingProfile = lists.homeProfiles.firstOrNull { it.id == s.homeProfileId }
        ?.takeIf { severalProfiles }
        ?.let { homeProfileName(it) }

    CompositionLocalProvider(
        LocalExpressivePalette provides palette,
        LocalSettingJumps provides jumps,
        LocalDrawerPicturePicker provides picturePicker,
        LocalHomeProfileRowMarks provides severalProfiles,
        // So AppIcons in the pickers (left-edge app, hidden apps) match the selected icon pack / themed
        // icons like every other surface.
        LocalIconPack provides s.iconPackPackage,
        LocalIconShape provides s.iconShape,
        LocalThemedIcons provides s.useThemedIcons,
        LocalThemeAllIcons provides s.themeAllIcons,
    ) {
        Surface(modifier = modifier.fillMaxSize(), color = palette.bg) {
            val screen = subScreen ?: SettingsCategory.fromKey(pageKey)?.key ?: SCREEN_MAIN
            val animate = systemAnimationsOn()
            val slide = with(LocalDensity.current) { PAGE_SLIDE.roundToPx() }
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    if (!animate) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        // A page that opens comes in from the end, one that is returned to from the start.
                        val from = if (screenDepth(targetState) > screenDepth(initialState)) slide else -slide
                        (slideInHorizontally(PageSlideSpec) { from } + fadeIn(PageFadeSpec)) togetherWith fadeOut(tween(100))
                    }
                },
                label = "settings screen",
            ) { shown ->
                // Whatever a page has OPEN (a folder's page and its "Delete?", a dialog, a text half typed) is
                // of the profile whose values the page showed when it was opened, and starts anew with
                // another profile ([pagesOf]). Which page is shown and how far it is scrolled are kept
                // above and stay. A question left open over a switch was answered for the profile that had
                // come on: the page tells its view model the profile it shows now, and a copy of a profile
                // has drawer folders with the same ids.
                key(pagesOf) {
                when (shown) {
                    SCREEN_MAIN -> SettingsMainPage(
                        onOpen = { pageKey = it.key },
                        baseColour = s.baseColour,
                        editingProfile = editingProfile,
                        scroll = mainScroll,
                        query = query,
                        onQueryChange = { query = it },
                        hits = hits,
                        onOpenHit = { hit ->
                            pendingJump = hit.setting.id
                            // A row of the main page itself is under the search: clearing it shows the row.
                            val category = hit.setting.category
                            if (category != null) pageKey = category.key else query = ""
                        },
                    )

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
}
