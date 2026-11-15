package org.arkikeskus.launcher.feature.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.screensaverPreferences
import org.arkikeskus.launcher.feature.screensaver.dream.DreamAccent
import org.arkikeskus.launcher.feature.screensaver.dream.DreamField
import org.arkikeskus.launcher.feature.screensaver.dream.DreamOptions
import org.arkikeskus.launcher.feature.screensaver.dream.DreamStyle
import org.arkikeskus.launcher.feature.screensaver.dream.DreamStylePreview
import org.arkikeskus.launcher.feature.screensaver.dream.dreamBrightnessOf
import org.arkikeskus.launcher.feature.screensaver.dream.readDreamOptions
import org.arkikeskus.launcher.feature.screensaver.dream.storedBrightnessPercent
import org.arkikeskus.launcher.feature.screensaver.dream.supports
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.expressive.expressiveCardGap
import org.arkikeskus.launcher.ui.expressive.expressiveCardShadow
import org.arkikeskus.launcher.ui.expressive.expressiveCardShape
import java.util.Locale
import kotlin.math.roundToInt
import org.arkikeskus.launcher.feature.screensaver.R as ScreensaverR

/** The tag of a style's thumbnail in the style picker. */
internal fun screensaverStyleTag(style: DreamStyle): String = "screensaver-style-${style.id}"

/** Width of one thumbnail in the style picker. */
private val STYLE_THUMBNAIL_WIDTH = 112.dp
private val STYLE_THUMBNAIL_SHAPE = RoundedCornerShape(12.dp)

/** The brightness slider works in whole per cent, 1 to 100; this is the level a new user starts from. */
private const val DEFAULT_BRIGHTNESS = DreamPreferences.DEFAULT_BRIGHTNESS_PERCENT / 100f

/**
 * The Screensaver page: every setting of the launcher's screensaver. The values live in the screensaver's own
 * file (the screensaver's service reads them when it starts), so each row reads from it and writes straight back;
 * nothing here goes through the launcher's settings. The style picker's thumbnails are drawn by the screensaver's
 * own code with the switches as they stand, so a change is seen before the screensaver ever starts.
 */
@Composable
internal fun ScreensaverRows() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val prefs = remember(context) { context.screensaverPreferences() }
    val showDeviceName = rememberDreamSwitch(prefs, DreamPreferences.KEY_SHOW_DEVICE_NAME, true)
    val showNotifications = rememberDreamSwitch(prefs, DreamPreferences.KEY_SHOW_NOTIFICATIONS, true)
    val showSeconds = rememberDreamSwitch(prefs, DreamPreferences.KEY_SHOW_SECONDS, true)
    val showDate = rememberDreamSwitch(prefs, DreamPreferences.KEY_SHOW_DATE, true)
    val showAlarm = rememberDreamSwitch(prefs, DreamPreferences.KEY_SHOW_ALARM, true)
    val showBatteryDetails = rememberDreamSwitch(prefs, DreamPreferences.KEY_SHOW_BATTERY_DETAILS, true)
    val showEstimate = rememberDreamSwitch(prefs, DreamPreferences.KEY_SHOW_ESTIMATE, true)
    val layoutSwapped = rememberDreamSwitch(prefs, DreamPreferences.KEY_LAYOUT_SWAPPED, false)
    val forcePortrait = rememberDreamSwitch(prefs, DreamPreferences.KEY_FORCE_PORTRAIT, false)
    val dim = rememberDreamSwitch(prefs, DreamPreferences.KEY_DIM_SCREENSAVER, false)
    val nightDim = rememberDreamSwitch(prefs, DreamPreferences.KEY_NIGHT_DIM, false)
    val nightStyleAuto = rememberDreamSwitch(prefs, DreamPreferences.KEY_NIGHT_STYLE_AUTO, false)
    val verticalShiftOnly = rememberDreamSwitch(prefs, DreamPreferences.KEY_VERTICAL_SHIFT_ONLY, false)
    val showCalendar = rememberDreamSwitch(prefs, DreamPreferences.KEY_SHOW_CALENDAR, false)
    val showCalendarTitles = rememberDreamSwitch(prefs, DreamPreferences.KEY_SHOW_CALENDAR_TITLES, true)
    val ownBrightness = rememberDreamSwitch(prefs, DreamPreferences.KEY_OWN_BRIGHTNESS, false)

    val stored = remember(prefs) { readDreamOptions(prefs) }
    var style by remember { mutableStateOf(stored.style) }
    var accent by remember { mutableStateOf(stored.accent) }
    var brightnessPercent by remember { mutableIntStateOf(storedBrightnessPercent(prefs)) }
    var nightStart by remember {
        mutableIntStateOf(prefs.minutes(DreamPreferences.KEY_NIGHT_DIM_START_MINUTES, DreamPreferences.DEFAULT_NIGHT_DIM_START_MINUTES))
    }
    var nightEnd by remember {
        mutableIntStateOf(prefs.minutes(DreamPreferences.KEY_NIGHT_DIM_END_MINUTES, DreamPreferences.DEFAULT_NIGHT_DIM_END_MINUTES))
    }
    // Which of the night dim's two times is being picked, if either: survives the dialog's own recreation.
    var pickingStart by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // While the brightness slider moves, this page itself is shown at the chosen level.
    val pageWindow = remember(context) { context.findActivity()?.window }

    // Both are granted and taken away on Android's own pages too, so they are read again on every return.
    var notificationAccess by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var calendarGranted by remember { mutableStateOf(context.holds(Manifest.permission.READ_CALENDAR)) }
    LifecycleResumeEffect(Unit) {
        notificationAccess = isNotificationAccessGranted(context)
        calendarGranted = context.holds(Manifest.permission.READ_CALENDAR)
        onPauseOrDispose { }
    }
    // Whether the calendar's dialog was already answered once here: a second dialog-less "no" is the lasting one.
    var calendarAsked by rememberSaveable { mutableStateOf(false) }
    val calendarRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarGranted = granted
        val activity = context.findActivity()
        if (!granted && calendarAsked && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
        ) {
            openAppDetailsSettings(context)
        }
        calendarAsked = true
    }

    // What the thumbnails draw with: the same options the screensaver would read right now.
    val previewOptions = DreamOptions(
        style = style,
        verticalShiftOnly = verticalShiftOnly.checked,
        showDeviceName = showDeviceName.checked,
        showNotifications = showNotifications.checked,
        showSeconds = showSeconds.checked,
        showDate = showDate.checked,
        showAlarm = showAlarm.checked,
        showBatteryDetails = showBatteryDetails.checked,
        showEstimate = showEstimate.checked,
        accent = accent,
        showCalendar = showCalendar.checked,
        showCalendarTitles = showCalendarTitles.checked,
    )

    SettingRow("SS1") {
        StylePicker(
            selected = style,
            options = previewOptions,
            onSelect = { chosen ->
                style = chosen
                prefs.edit().putString(DreamPreferences.KEY_STYLE, chosen.id).apply()
            },
        )
    }

    PageHeading(stringResource(R.string.settings_screensaver_start))
    SettingRow("SS2") {
        ExpressiveActionRow(
            label = stringResource(ScreensaverR.string.open_screensaver_settings),
            description = stringResource(ScreensaverR.string.screensaver_charging_hint),
            trailingIcon = LauncherIcons.OpenInNew,
            onClick = { openDreamSettings(context) },
        )
    }

    // A switch for something the chosen style has no place for is disabled: it keeps its value for the styles
    // that do.
    PageHeading(stringResource(R.string.settings_screensaver_shows))
    SettingRow("SS5") {
        SwitchRow(
            label = stringResource(ScreensaverR.string.dream_device_name_title),
            description = stringResource(ScreensaverR.string.dream_device_name_description),
            checked = showDeviceName.checked,
            onCheckedChange = showDeviceName::set,
            enabled = style.supports(DreamField.DEVICE_NAME),
        )
    }
    SettingRow("SS6") {
        FieldSwitch(ScreensaverR.string.dream_show_seconds, showSeconds, style.supports(DreamField.SECONDS))
    }
    SettingRow("SS7") {
        FieldSwitch(ScreensaverR.string.dream_show_date, showDate, style.supports(DreamField.DATE))
    }
    SettingRow("SS8") {
        FieldSwitch(ScreensaverR.string.dream_show_alarm, showAlarm, style.supports(DreamField.ALARM))
    }
    SettingRow("SS9") {
        SwitchRow(
            label = stringResource(ScreensaverR.string.dream_notifications_title),
            // Switched on without the access, the row says what is missing instead of what it would show.
            description = stringResource(
                if (showNotifications.checked && !notificationAccess) {
                    ScreensaverR.string.dream_notifications_access_missing
                } else {
                    ScreensaverR.string.dream_notifications_description
                },
            ),
            checked = showNotifications.checked,
            onCheckedChange = showNotifications::set,
            enabled = style.supports(DreamField.NOTIFICATIONS),
        )
    }
    SettingRow("SS10") {
        FieldSwitch(ScreensaverR.string.dream_show_battery_details, showBatteryDetails, style.supports(DreamField.BATTERY_DETAILS))
    }
    SettingRow("SS11") {
        FieldSwitch(ScreensaverR.string.dream_show_estimate, showEstimate, style.supports(DreamField.ESTIMATE))
    }
    SettingRow("SS12") {
        AccentPicker(
            selected = accent,
            // The accent is the colour of the seconds, so it has a use only where they show.
            enabled = style.supports(DreamField.SECONDS) && showSeconds.checked,
            onSelect = { chosen ->
                accent = chosen
                prefs.edit().putString(DreamPreferences.KEY_ACCENT, chosen.id).apply()
            },
        )
    }
    // The calendar rows are the Gauges style's own.
    if (style == DreamStyle.GAUGES) {
        SettingRow("SS13") {
            SwitchRow(
                label = stringResource(ScreensaverR.string.dream_calendar_title),
                description = stringResource(
                    if (showCalendar.checked && !calendarGranted) {
                        ScreensaverR.string.dream_calendar_permission_missing
                    } else {
                        ScreensaverR.string.dream_calendar_description
                    },
                ),
                checked = showCalendar.checked,
                onCheckedChange = { on ->
                    showCalendar.set(on)
                    // The permission is asked for here, when the rows are switched on.
                    if (on && !context.holds(Manifest.permission.READ_CALENDAR)) {
                        calendarRequest.launch(Manifest.permission.READ_CALENDAR)
                    }
                },
            )
        }
        SettingRow("SS14") {
            SwitchRow(
                label = stringResource(ScreensaverR.string.dream_calendar_titles_title),
                description = stringResource(ScreensaverR.string.dream_calendar_titles_description),
                checked = showCalendarTitles.checked,
                onCheckedChange = showCalendarTitles::set,
                enabled = showCalendar.checked,
            )
        }
    }

    PageHeading(stringResource(R.string.settings_screensaver_layout))
    SettingRow("SS15") {
        SwitchRow(
            label = stringResource(ScreensaverR.string.dream_flip_title),
            description = stringResource(ScreensaverR.string.dream_flip_description),
            checked = layoutSwapped.checked,
            onCheckedChange = layoutSwapped::set,
        )
    }
    SettingRow("SS16") {
        SwitchRow(
            label = stringResource(ScreensaverR.string.force_portrait_title),
            description = stringResource(ScreensaverR.string.force_portrait_description),
            checked = forcePortrait.checked,
            onCheckedChange = forcePortrait::set,
        )
    }
    SettingRow("SS17") {
        SwitchRow(
            label = stringResource(ScreensaverR.string.dream_vertical_shift_title),
            description = stringResource(ScreensaverR.string.dream_vertical_shift_description),
            checked = verticalShiftOnly.checked,
            onCheckedChange = verticalShiftOnly::set,
        )
    }

    PageHeading(stringResource(R.string.settings_screensaver_night))
    SettingRow("SS18") {
        SwitchRow(
            label = stringResource(ScreensaverR.string.dim_screensaver_title),
            description = stringResource(ScreensaverR.string.dim_screensaver_description),
            checked = dim.checked,
            onCheckedChange = dim::set,
        )
    }
    SettingRow("SS19") {
        SwitchRow(
            label = stringResource(ScreensaverR.string.night_dim_title),
            description = stringResource(ScreensaverR.string.night_dim_description),
            checked = nightDim.checked,
            onCheckedChange = nightDim::set,
        )
    }
    if (nightDim.checked) {
        SettingRow("SS20") {
            NightTimesRow(
                startMinutes = nightStart,
                endMinutes = nightEnd,
                onPickStart = { pickingStart = true },
                onPickEnd = { pickingStart = false },
            )
        }
    }
    SettingRow("SS21") {
        // The Night style follows the night dim's hours, so it needs the night dim.
        SwitchRow(
            label = stringResource(ScreensaverR.string.dream_night_style_title),
            description = stringResource(ScreensaverR.string.dream_night_style_description),
            checked = nightStyleAuto.checked,
            onCheckedChange = nightStyleAuto::set,
            enabled = nightDim.checked,
        )
    }
    SettingRow("SS22") {
        SwitchRow(
            label = stringResource(ScreensaverR.string.dream_brightness_title),
            description = stringResource(ScreensaverR.string.dream_brightness_description),
            checked = ownBrightness.checked,
            onCheckedChange = ownBrightness::set,
        )
    }
    if (ownBrightness.checked) {
        // The slider can go away in the middle of a move, with no word of the move having ended: the switch
        // above turned off with another finger, or the page closed. Whenever it goes, the page gives the
        // phone's brightness back, and a level that was never stored is forgotten, so the slider comes back
        // where the screensaver's level is.
        DisposableEffect(pageWindow) {
            onDispose {
                pageWindow?.showBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                brightnessPercent = storedBrightnessPercent(prefs)
            }
        }
        SettingRow("SS23") {
            ScaleSliderRow(
                label = stringResource(R.string.settings_screensaver_brightness_level),
                value = brightnessPercent / 100f,
                onValueChange = { value ->
                    brightnessPercent = (value * 100f).roundToInt().coerceIn(1, 100)
                    pageWindow?.showBrightness(dreamBrightnessOf(brightnessPercent))
                },
                valueRange = 0.01f..1f,
                valueText = { resources.getString(R.string.settings_percent, (it * 100f).roundToInt()) },
                description = stringResource(ScreensaverR.string.dream_brightness_hint),
                step = 0.01f,
                default = DEFAULT_BRIGHTNESS,
                onChangeFinished = {
                    prefs.edit().putInt(DreamPreferences.KEY_BRIGHTNESS_PERCENT, brightnessPercent).apply()
                    pageWindow?.showBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                },
            )
        }
    }

    pickingStart?.let { start ->
        NightTimeDialog(
            initialMinutes = if (start) nightStart else nightEnd,
            onConfirm = { minutes ->
                if (start) {
                    nightStart = minutes
                    prefs.edit().putInt(DreamPreferences.KEY_NIGHT_DIM_START_MINUTES, minutes).apply()
                } else {
                    nightEnd = minutes
                    prefs.edit().putInt(DreamPreferences.KEY_NIGHT_DIM_END_MINUTES, minutes).apply()
                }
                pickingStart = null
            },
            onDismiss = { pickingStart = null },
        )
    }
}

/** A switch for one thing the screensaver can show; [supported] says whether the chosen style has a place for it. */
@Composable
private fun FieldSwitch(label: Int, switch: DreamSwitch, supported: Boolean) {
    SwitchRow(
        label = stringResource(label),
        description = null,
        checked = switch.checked,
        onCheckedChange = switch::set,
        enabled = supported,
    )
}

/** The frame of a row that is more than a label and a switch: the card of the other rows. */
@Composable
private fun RowCard(content: @Composable () -> Unit) {
    Surface(
        color = LocalExpressivePalette.current.surfaceHi,
        shape = expressiveCardShape(),
        shadowElevation = expressiveCardShadow(),
        modifier = Modifier.fillMaxWidth().padding(bottom = expressiveCardGap()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) { content() }
    }
}

/**
 * The styles side by side, each as a thumbnail drawn by the screensaver's own code with its name under it; the
 * chosen one is framed. The row scrolls sideways when it does not fit.
 */
@Composable
private fun StylePicker(selected: DreamStyle, options: DreamOptions, onSelect: (DreamStyle) -> Unit) {
    val p = LocalExpressivePalette.current
    RowCard {
        RowLabel(stringResource(ScreensaverR.string.dream_style_title))
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (style in DreamStyle.entries) {
                val isSelected = style == selected
                Column(
                    modifier = Modifier
                        .width(STYLE_THUMBNAIL_WIDTH)
                        .testTag(screensaverStyleTag(style))
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(style) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DreamStylePreview(
                        style = style,
                        options = options,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(STYLE_THUMBNAIL_SHAPE)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) p.accent else p.faint,
                                shape = STYLE_THUMBNAIL_SHAPE,
                            ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(styleName(style)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) p.accent else p.dim,
                    )
                }
            }
        }
    }
}

/** The colour of the clock's seconds: a ready-made handful, each one sitting well beside the battery's colours. */
@Composable
private fun AccentPicker(selected: DreamAccent, enabled: Boolean, onSelect: (DreamAccent) -> Unit) {
    val p = LocalExpressivePalette.current
    RowCard {
        RowLabel(stringResource(ScreensaverR.string.dream_accent_title), color = if (enabled) p.text else p.dim)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            for (accent in DreamAccent.entries) {
                ColorSwatch(
                    argb = accent.color.toArgb(),
                    selected = accent == selected,
                    enabled = enabled,
                    onClick = { onSelect(accent) },
                )
            }
        }
    }
}

/** The night dim's two times, each a button that opens a clock to pick it on. */
@Composable
private fun NightTimesRow(startMinutes: Int, endMinutes: Int, onPickStart: () -> Unit, onPickEnd: () -> Unit) {
    val p = LocalExpressivePalette.current
    RowCard {
        RowLabel(stringResource(R.string.settings_screensaver_night_times))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPickStart) {
                Text(stringResource(ScreensaverR.string.night_dim_start_label, minutesText(startMinutes)), color = p.accent)
            }
            TextButton(onClick = onPickEnd) {
                Text(stringResource(ScreensaverR.string.night_dim_end_label, minutesText(endMinutes)), color = p.accent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightTimeDialog(initialMinutes: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        text = { TimePicker(state = state) },
    )
}

/** Minutes of the day as a clock time: 1320 is "22:00". */
internal fun minutesText(minutes: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)

private fun styleName(style: DreamStyle): Int = when (style) {
    DreamStyle.GLOW -> ScreensaverR.string.dream_style_glow
    DreamStyle.MINIMAL -> ScreensaverR.string.dream_style_minimal
    DreamStyle.NIGHT -> ScreensaverR.string.dream_style_night
    DreamStyle.RING -> ScreensaverR.string.dream_style_ring
    DreamStyle.GAUGES -> ScreensaverR.string.dream_style_gauges
    DreamStyle.EDGE -> ScreensaverR.string.dream_style_edge
}

/** Android's own screensaver page: which screensaver is used, and when it starts. */
private fun openDreamSettings(context: Context) {
    val intent = Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A phone without that page: its display settings are the nearest.
        runCatching {
            context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Shows the settings page itself at [brightness], or at the phone's own again. */
private fun Window.showBrightness(brightness: Float) {
    attributes = attributes.apply { screenBrightness = brightness }
}

private fun Context.holds(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** A stored whole number; a value of another type (a restored file) counts as [default]. */
private fun SharedPreferences.minutes(key: String, default: Int): Int =
    try {
        getInt(key, default)
    } catch (_: ClassCastException) {
        default
    }

/** One on/off setting of the screensaver: shown from the file and written straight back. */
private class DreamSwitch(private val prefs: SharedPreferences, private val key: String, initial: Boolean) {
    var checked by mutableStateOf(initial)
        private set

    fun set(value: Boolean) {
        checked = value
        prefs.edit().putBoolean(key, value).apply()
    }
}

@Composable
private fun rememberDreamSwitch(prefs: SharedPreferences, key: String, default: Boolean): DreamSwitch =
    remember(prefs, key) {
        val stored = try {
            prefs.getBoolean(key, default)
        } catch (_: ClassCastException) {
            default
        }
        DreamSwitch(prefs, key, stored)
    }
