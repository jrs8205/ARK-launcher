package org.arkikeskus.launcher.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** The tag of a page's header (the back arrow and the page's name). */
internal const val PAGE_HEADER_TAG = "settings-page-header"

/**
 * One category's page: its header with the way back, then its rows. Back (the arrow or the system's) calls [onBack].
 * The App drawer page opens its two sub-screens through [onOpenHiddenApps] and [onOpenFolders]. The backup category
 * has no page of rows: it opens the backup screen itself.
 */
@Composable
internal fun SettingsCategoryPage(
    category: SettingsCategory,
    s: LauncherSettings,
    lists: SettingsLists,
    actions: SettingsActions,
    blurAvailability: BlurAvailability,
    onBack: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenFolders: () -> Unit,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        SettingsPageHeader(title = stringResource(category.title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
        ) {
            when (category) {
                SettingsCategory.HOME -> HomeGridRows(s, actions)
                SettingsCategory.DOCK -> DockRows(s, actions)
                SettingsCategory.DRAWER -> DrawerRows(
                    s = s,
                    lists = lists,
                    actions = actions,
                    blurAvailability = blurAvailability,
                    onOpenHiddenApps = onOpenHiddenApps,
                    onOpenFolders = onOpenFolders,
                )
                SettingsCategory.FOLDERS -> FolderAppearanceRows(s, actions)
                SettingsCategory.WIDGETS -> {
                    PageHeading(stringResource(R.string.settings_clock_widget), first = true)
                    WeatherToggleRow(s, actions)
                    ClockWidgetLookRows(s, actions)
                    WeatherIconRows(s, actions)
                    WeatherDetailRows(s, lists.apps, actions)
                    PageHeading(stringResource(R.string.settings_heading_notif_widget))
                    NotificationWidgetRows(s, actions)
                    PageHeading(stringResource(R.string.settings_heading_people_widget))
                    PeopleWidgetRows(s, actions)
                    PageHeading(stringResource(R.string.settings_heading_all_widgets))
                    WidgetGeneralRows(s, actions)
                }
                SettingsCategory.ICONS -> IconsRows(s, lists.iconPacks, actions)
                SettingsCategory.GESTURES -> GesturesRows(s, lists.apps, actions)
                SettingsCategory.BARS -> {
                    SystemBarsHideRows(s, actions)
                    OwnStatusBarRows(s, actions)
                }
                SettingsCategory.NOTIFICATIONS -> NotificationsRows(s, actions)
                SettingsCategory.SOUNDS -> LockSoundsSettings(
                    enabled = s.lockSoundsEnabled,
                    lockUri = s.lockSoundUri,
                    unlockUri = s.unlockSoundUri,
                    onSetEnabled = actions::setLockSoundsEnabled,
                    onSetSound = actions::setLockSound,
                )
                SettingsCategory.PERMISSIONS -> PermissionsRows()
                SettingsCategory.BACKUP -> Unit
                SettingsCategory.ABOUT -> {
                    PageHeading(stringResource(R.string.settings_updates), first = true)
                    UpdatesRow()
                    PageHeading(stringResource(R.string.settings_feedback))
                    FeedbackRows(onOpen = { openLink(context, it) })
                    VersionLine()
                }
            }
        }
    }
}

/** A page's header: the back arrow, a 48 dp target, and the page's name as a heading. A long name wraps. */
@Composable
internal fun SettingsPageHeader(title: String, onBack: () -> Unit) {
    val p = LocalExpressivePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PAGE_HEADER_TAG)
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(LauncherIcons.ArrowBack),
                contentDescription = stringResource(R.string.settings_back),
                tint = p.text,
            )
        }
        Text(
            text = title,
            color = p.text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
        )
    }
}

/** A heading between the groups of a page that gathers more than one; the [first] one sits closer to the header. */
@Composable
private fun PageHeading(text: String, first: Boolean = false) {
    Text(
        text = text,
        color = LocalExpressivePalette.current.text,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        modifier = Modifier
            .padding(top = if (first) 8.dp else 26.dp, start = 4.dp, bottom = 12.dp)
            .semantics { heading() },
    )
}
