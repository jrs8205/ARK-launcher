package org.arkikeskus.launcher.feature.settings

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.LongPressScale
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow

/** Settings > Gestures: the swipes, the left-edge app, double tap to lock and the two press timings. */
@Composable
internal fun GesturesRows(s: LauncherSettings, allApps: List<AppItem>, actions: SettingsActions) {
    val resources = LocalResources.current
    var showLeftSwipePicker by rememberSaveable { mutableStateOf(false) }
    SettingRow("G1") {
        SwitchRow(stringResource(R.string.settings_swipe_up_drawer), s.swipeUpForDrawer, actions::setSwipeUp)
    }
    SettingRow("G2") {
        SwitchRow(stringResource(R.string.settings_swipe_down_notif), s.swipeDownForNotifications, actions::setSwipeDown)
    }
    SettingRow("G3") {
        SwitchRow(
            stringResource(R.string.settings_folder_swipe_first_app),
            stringResource(R.string.settings_folder_swipe_first_app_desc),
            s.folderSwipeOpensFirstApp,
            actions::setFolderSwipeOpensFirstApp,
        )
    }
    val leftSwipeLabel = allApps.firstOrNull { it.key == s.leftSwipeAppKey }?.label
        ?: stringResource(R.string.settings_left_edge_none)
    SettingRow("G4") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_left_edge),
            description = leftSwipeLabel,
        ) { showLeftSwipePicker = true }
    }
    DoubleTapLockToggle(enabled = s.doubleTapToLock, onSetEnabled = actions::setDoubleTapToLock)
    // Read out in milliseconds of this phone's own long-press timeout times the setting, so the
    // number is the real wait here and the default chip names it.
    val systemLongPress = rememberSystemLongPressTimeout()
    SettingRow("G6") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_long_press_delay),
            description = stringResource(R.string.settings_long_press_delay_desc),
            value = s.longPressScale,
            onValueChange = actions::setLongPressScale,
            valueRange = LongPressScale.MIN..LongPressScale.MAX,
            step = 0.1f,
            valueText = { resources.getString(R.string.settings_millis, (systemLongPress * it).roundToInt()) },
        )
    }
    SettingRow("G7") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_icon_swipe_distance),
            description = stringResource(R.string.settings_icon_swipe_distance_desc),
            value = s.iconSwipeDistance,
            onValueChange = actions::setIconSwipeDistance,
            valueRange = 1f..4f,
            step = 0.25f,
            valueText = { resources.getString(R.string.settings_percent, (it * 100).roundToInt()) },
        )
    }
    if (showLeftSwipePicker) {
        SingleAppPicker(
            apps = allApps,
            onPick = { key ->
                actions.setLeftSwipeAppKey(key)
                showLeftSwipePicker = false
            },
            onDismiss = { showLeftSwipePicker = false },
        )
    }
}

/** The home module's lock service; referenced by name (feature modules don't depend on each other). */
private const val LOCK_SERVICE_CLASS = "org.arkikeskus.launcher.feature.home.LockAccessibilityService"

private fun lockServiceEnabled(context: android.content.Context): Boolean {
    val enabled = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val target = android.content.ComponentName(context.packageName, LOCK_SERVICE_CLASS)
    return enabled.split(':').any { android.content.ComponentName.unflattenFromString(it) == target }
}

/** Double-tap-to-lock toggle. Locking needs the lock accessibility service (used ONLY for
 *  performGlobalAction(LOCK_SCREEN)); enabling the switch opens the system accessibility settings
 *  when the service isn't allowed yet, and a grant row stays visible until it is. */
@Composable
private fun DoubleTapLockToggle(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    var serviceEnabled by remember { mutableStateOf(lockServiceEnabled(context)) }
    // Re-check when returning from the system accessibility settings.
    LifecycleResumeEffect(Unit) {
        serviceEnabled = lockServiceEnabled(context)
        onPauseOrDispose { }
    }
    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    SettingRow("G5") {
        SwitchRow(stringResource(R.string.settings_double_tap_lock), enabled) { wantOn ->
            onSetEnabled(wantOn)
            if (wantOn && !lockServiceEnabled(context)) openAccessibilitySettings()
        }
    }
    if (enabled && !serviceEnabled) {
        SettingRow("G5a") {
            ExpressiveActionRow(
                label = stringResource(R.string.settings_double_tap_lock_grant),
                description = stringResource(R.string.settings_double_tap_lock_grant_desc),
            ) { openAccessibilitySettings() }
        }
    }
}
