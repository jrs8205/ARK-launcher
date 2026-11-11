package org.arkikeskus.launcher.feature.settings

import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** The home grid and its pages: columns, rows, names, the glass layer, the page indicator, paging and the lock. */
@Composable
internal fun HomeGridRows(s: LauncherSettings, actions: SettingsActions) {
    val percentText = percentReadout()
    var showHomeMarkerPicker by rememberSaveable { mutableStateOf(false) }
    var showPageSpeedPicker by rememberSaveable { mutableStateOf(false) }
    SettingRow("H1") {
        StepperRow(stringResource(R.string.settings_columns), s.homeColumns, SettingsRepository.MIN_COLUMNS, SettingsRepository.MAX_COLUMNS, actions::setHomeColumns)
    }
    SettingRow("H2") {
        StepperRow(stringResource(R.string.settings_rows), s.homeRows, SettingsRepository.MIN_ROWS, SettingsRepository.MAX_ROWS, actions::setHomeRows)
    }
    SettingRow("H3") {
        SwitchRow(stringResource(R.string.settings_show_labels), s.showHomeLabels, actions::setShowHomeLabels)
    }
    SettingRow("H4") {
        SwitchRow(stringResource(R.string.settings_two_line_labels), s.twoLineHomeLabels, actions::setTwoLineHomeLabels)
    }
    SettingRow("H5") {
        SwitchRow(stringResource(R.string.settings_show_folder_names), s.showHomeFolderNames, actions::setShowHomeFolderNames)
    }
    SettingRow("H6") {
        StepperRow(
            stringResource(R.string.settings_folder_columns), s.folderColumns,
            SettingsRepository.MIN_FOLDER_COLUMNS, SettingsRepository.MAX_FOLDER_COLUMNS, actions::setFolderColumns,
        )
    }
    SettingRow("H7") {
        SwitchRow(
            stringResource(R.string.settings_glass_panel),
            stringResource(R.string.settings_glass_panel_desc),
            s.homeGlassPanel,
            actions::setHomeGlassPanel,
        )
    }
    if (s.homeGlassPanel) {
        SettingRow("H8") {
            ScaleSliderRow(
                label = stringResource(R.string.settings_glass_strength),
                value = s.homeGlassOpacity,
                onValueChange = actions::setHomeGlassOpacity,
                valueRange = SettingsRepository.MIN_GLASS_OPACITY..SettingsRepository.MAX_GLASS_OPACITY,
                valueText = percentText,
                default = LauncherSettings.DEFAULT_GLASS_OPACITY,
            )
        }
    }
    SettingRow("H9") {
        SwitchRow(stringResource(R.string.settings_page_indicator), s.showPageIndicator, actions::setShowPageIndicator)
    }
    if (s.showPageIndicator) {
        SettingRow("H10") {
            ExpressiveActionRow(
                label = stringResource(R.string.settings_home_page_marker),
                description = stringResource(
                    when (s.homePageMarker) {
                        LauncherSettings.MARKER_HOUSE -> R.string.settings_home_page_marker_house
                        else -> R.string.settings_home_page_marker_ring
                    },
                ),
            ) { showHomeMarkerPicker = true }
        }
    }
    SettingRow("H11") {
        PageSwitchSpeedRow(selected = s.pageSwitchSpeed) { showPageSpeedPicker = true }
    }
    SettingRow("H12") {
        SwitchRow(
            stringResource(R.string.settings_infinite_scroll),
            stringResource(R.string.settings_infinite_scroll_desc),
            s.infiniteScroll,
            actions::setInfiniteScroll,
        )
    }
    SettingRow("H13") {
        SwitchRow(stringResource(R.string.settings_lock_desktop), s.desktopLocked, actions::setDesktopLocked)
    }
    if (showHomeMarkerPicker) {
        ChoicePicker(
            title = R.string.settings_home_page_marker,
            options = listOf(
                LauncherSettings.MARKER_RING to R.string.settings_home_page_marker_ring,
                LauncherSettings.MARKER_HOUSE to R.string.settings_home_page_marker_house,
            ),
            selected = s.homePageMarker,
            onPick = {
                actions.setHomePageMarker(it)
                showHomeMarkerPicker = false
            },
            onDismiss = { showHomeMarkerPicker = false },
        )
    }
    if (showPageSpeedPicker) {
        ChoicePicker(
            title = R.string.settings_page_switch_speed,
            options = listOf(
                LauncherSettings.SPEED_NORMAL to R.string.settings_page_speed_normal,
                LauncherSettings.SPEED_FAST to R.string.settings_page_speed_fast,
                LauncherSettings.SPEED_FASTEST to R.string.settings_page_speed_fastest,
            ),
            selected = s.pageSwitchSpeed,
            onPick = { speed ->
                actions.setPageSwitchSpeed(speed)
                showPageSpeedPicker = false
            },
            onDismiss = { showPageSpeedPicker = false },
        )
    }
}

/** Hiding the system's status bar and navigation bar on the home screen. */
@Composable
internal fun SystemBarsHideRows(s: LauncherSettings, actions: SettingsActions) {
    SettingRow("H14") {
        SwitchRow(
            stringResource(R.string.settings_hide_status_bar),
            s.hideSystemStatusBar,
            actions::setHideSystemStatusBar,
        )
    }
    SettingRow("H15") {
        SwitchRow(
            stringResource(R.string.settings_hide_navigation_bar),
            stringResource(R.string.settings_hide_navigation_bar_desc),
            s.hideSystemNavigationBar,
            actions::setHideSystemNavigationBar,
        )
    }
}

/** What applies to every widget: the tonal background and the edges in line with the icons. */
@Composable
internal fun WidgetGeneralRows(s: LauncherSettings, actions: SettingsActions) {
    SettingRow("H16") {
        SwitchRow(stringResource(R.string.settings_widget_tonal), s.widgetTonalBackground, actions::setWidgetTonalBackground)
    }
    SettingRow("H17") {
        SwitchRow(
            stringResource(R.string.settings_widget_inset),
            stringResource(R.string.settings_widget_inset_desc),
            s.widgetEdgesAlignWithIcons,
            actions::setWidgetEdgesAlignWithIcons,
        )
    }
}

/** The weather in the clock widget, on or off. */
@Composable
internal fun WeatherToggleRow(s: LauncherSettings, actions: SettingsActions) {
    SettingRow("H18") {
        WeatherToggle(enabled = s.showWeather, onSetEnabled = actions::setShowWeather)
    }
}

/** What a tap on the weather opens and where the place name comes from; nothing while the weather is off. */
@Composable
internal fun WeatherDetailRows(s: LauncherSettings, allApps: List<AppItem>, actions: SettingsActions) {
    var showWeatherAppPicker by rememberSaveable { mutableStateOf(false) }
    if (s.showWeather) {
        SettingRow("H19") {
            WeatherAppRow(appLabel = allApps.firstOrNull { it.key == s.weatherAppKey }?.label) {
                showWeatherAppPicker = true
            }
        }
        SettingRow("H20") {
            SwitchRow(
                stringResource(R.string.settings_network_place_names),
                stringResource(R.string.settings_network_place_names_desc),
                s.networkPlaceNames,
                actions::setNetworkPlaceNames,
            )
        }
    }
    if (showWeatherAppPicker) {
        SingleAppPicker(
            apps = allApps,
            onPick = { key ->
                actions.setWeatherAppKey(key)
                showWeatherAppPicker = false
            },
            onDismiss = { showWeatherAppPicker = false },
        )
    }
}

/** How the notifications widget shows a count. */
@Composable
internal fun NotificationWidgetRows(s: LauncherSettings, actions: SettingsActions) {
    var showCountStylePicker by rememberSaveable { mutableStateOf(false) }
    SettingRow("H21") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_notif_widget_count),
            description = stringResource(
                when (s.notificationWidgetCountStyle) {
                    LauncherSettings.COUNT_DOT -> R.string.settings_notif_widget_count_dot
                    LauncherSettings.COUNT_NONE -> R.string.settings_notif_widget_count_none
                    else -> R.string.settings_notif_widget_count_number
                },
            ),
        ) { showCountStylePicker = true }
    }
    if (showCountStylePicker) {
        ChoicePicker(
            title = R.string.settings_notif_widget_count,
            options = listOf(
                LauncherSettings.COUNT_NUMBER to R.string.settings_notif_widget_count_number,
                LauncherSettings.COUNT_DOT to R.string.settings_notif_widget_count_dot,
                LauncherSettings.COUNT_NONE to R.string.settings_notif_widget_count_none,
            ),
            selected = s.notificationWidgetCountStyle,
            onPick = {
                actions.setNotificationWidgetCountStyle(it)
                showCountStylePicker = false
            },
            onDismiss = { showCountStylePicker = false },
        )
    }
}

/** The people widget: privacy, other apps' notifications and batch delivery. */
@Composable
internal fun PeopleWidgetRows(s: LauncherSettings, actions: SettingsActions) {
    var showPeoplePrivacyPicker by rememberSaveable { mutableStateOf(false) }
    var showBatchTimesEditor by rememberSaveable { mutableStateOf(false) }
    SettingRow("H22") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_people_privacy),
            description = stringResource(
                when (s.peoplePrivacy) {
                    LauncherSettings.PRIVACY_SENDER -> R.string.settings_people_privacy_sender
                    LauncherSettings.PRIVACY_COUNT -> R.string.settings_people_privacy_count
                    else -> R.string.settings_people_privacy_all
                },
            ),
        ) { showPeoplePrivacyPicker = true }
    }
    SettingRow("H23") {
        SwitchRow(
            stringResource(R.string.settings_people_show_apps),
            s.peopleShowApps,
            actions::setPeopleShowApps,
        )
    }
    SettingRow("H24") {
        SwitchRow(
            stringResource(R.string.settings_people_batch),
            s.peopleBatchEnabled,
            actions::setPeopleBatchEnabled,
        )
    }
    if (s.peopleBatchEnabled) {
        SettingRow("H25") {
            ExpressiveActionRow(
                label = stringResource(R.string.settings_people_batch_times),
                description = s.peopleBatchTimes.replace(",", ", "),
            ) { showBatchTimesEditor = true }
        }
    }
    if (showPeoplePrivacyPicker) {
        ChoicePicker(
            title = R.string.settings_people_privacy,
            options = listOf(
                LauncherSettings.PRIVACY_ALL to R.string.settings_people_privacy_all,
                LauncherSettings.PRIVACY_SENDER to R.string.settings_people_privacy_sender,
                LauncherSettings.PRIVACY_COUNT to R.string.settings_people_privacy_count,
            ),
            selected = s.peoplePrivacy,
            onPick = {
                actions.setPeoplePrivacy(it)
                showPeoplePrivacyPicker = false
            },
            onDismiss = { showPeoplePrivacyPicker = false },
        )
    }
    if (showBatchTimesEditor) {
        BatchTimesEditor(
            initial = s.peopleBatchTimes.replace(",", ", "),
            onSave = {
                actions.setPeopleBatchTimes(it)
                showBatchTimesEditor = false
            },
            onDismiss = { showBatchTimesEditor = false },
        )
    }
}

/** The launcher's own status bar and its background. */
@Composable
internal fun OwnStatusBarRows(s: LauncherSettings, actions: SettingsActions) {
    SettingRow("H26") {
        StatusBarToggle(enabled = s.showStatusBar, onSetEnabled = actions::setShowStatusBar)
    }
    if (s.showStatusBar) {
        SettingRow("H27") {
            StatusBarScrimRow(s.statusBarScrimOpacity, actions::setStatusBarScrimOpacity)
        }
    }
}

/** Free-text "HH:mm, HH:mm" editor for the people batch delivery times. */
@Composable
private fun BatchTimesEditor(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalExpressivePalette.current
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceHi,
        title = { Text(stringResource(R.string.settings_people_batch_times), color = p.text) },
        text = {
            Column {
                Text(stringResource(R.string.settings_people_batch_times_hint), color = p.text, fontSize = 13.sp)
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.settings_people_batch_times_save), color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close), color = Accent) }
        },
    )
}

/** Settings > Home: how fast the home pager settles on a page (issue #18); the current choice sits under the label. */
@Composable
internal fun PageSwitchSpeedRow(selected: String, onClick: () -> Unit) {
    ExpressiveActionRow(
        label = stringResource(R.string.settings_page_switch_speed),
        description = stringResource(
            when (selected) {
                LauncherSettings.SPEED_FAST -> R.string.settings_page_speed_fast
                LauncherSettings.SPEED_FASTEST -> R.string.settings_page_speed_fastest
                else -> R.string.settings_page_speed_normal
            },
        ),
        onClick = onClick,
    )
}

/** Settings > Home: the app a tap on the smartspace weather opens, named under the label (None = plain text). */
@Composable
internal fun WeatherAppRow(appLabel: String?, onClick: () -> Unit) {
    ExpressiveActionRow(
        label = stringResource(R.string.settings_weather_app),
        description = appLabel ?: stringResource(R.string.settings_left_edge_none),
        onClick = onClick,
    )
}

/** Smartspace-weather toggle. Turns on regardless; when enabled it also asks for the coarse location
 *  the Open-Meteo query needs (the widget hides the weather until the permission is granted). */
@Composable
private fun WeatherToggle(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* shows once granted */ }
    SwitchRow(stringResource(R.string.settings_show_weather), enabled) { wantOn ->
        onSetEnabled(wantOn)
        if (wantOn &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

/** Status-bar toggle. Turns on regardless (battery + Wi-Fi need no permission); when enabled it also
 *  asks for READ_PHONE_STATE so the mobile-signal indicator can show (battery/Wi-Fi work without it). */
@Composable
private fun StatusBarToggle(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* signal shows if granted */ }
    SwitchRow(stringResource(R.string.settings_status_bar), enabled) { wantOn ->
        onSetEnabled(wantOn)
        if (wantOn &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(android.Manifest.permission.READ_PHONE_STATE)
        }
    }
}

/** The status bar background darkness as a percent of black: 0 % transparent, 100 % solid black, in
 *  5 % steps with a tick of haptic feedback on each, and a chip back to the default. */
@Composable
internal fun StatusBarScrimRow(value: Float, onValueChange: (Float) -> Unit) {
    val resources = LocalResources.current
    ScaleSliderRow(
        label = stringResource(R.string.settings_status_bar_scrim),
        value = value,
        onValueChange = onValueChange,
        valueRange = 0f..1f,
        valueText = { resources.getString(R.string.settings_percent, scalePercent(it)) },
        default = LauncherSettings.DEFAULT_STATUS_BAR_SCRIM,
    )
}
