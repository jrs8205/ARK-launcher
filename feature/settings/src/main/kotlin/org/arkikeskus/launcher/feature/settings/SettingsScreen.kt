package org.arkikeskus.launcher.feature.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.feature.backup.BackupScreen
import org.arkikeskus.launcher.feature.updater.UpdateSection
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.DefaultLauncher
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.NotificationBadge
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.DarkExpressivePalette
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.ExpressiveCard
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.ExpressiveSectionTitle
import org.arkikeskus.launcher.ui.expressive.LightExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val allApps by viewModel.apps.collectAsStateWithLifecycle()
    val hiddenKeys by viewModel.hiddenKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val previewIcon = rememberLauncherIconBitmap()
    var showHiddenManager by remember { mutableStateOf(false) }
    var showLeftSwipePicker by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var isDefaultLauncher by remember { mutableStateOf(DefaultLauncher.isDefault(context)) }
    // Re-check after the user returns from the system "default home app" / role chooser.
    val setDefaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { isDefaultLauncher = DefaultLauncher.isDefault(context) }
    val palette = if (isSystemInDarkTheme()) DarkExpressivePalette else LightExpressivePalette

    CompositionLocalProvider(LocalExpressivePalette provides palette) {
        if (showBackup) {
            BackupScreen(onBack = { showBackup = false }, modifier = modifier.fillMaxSize())
            return@CompositionLocalProvider
        }

        if (showHiddenManager) {
            HiddenAppsManager(
                apps = allApps,
                hiddenKeys = hiddenKeys,
                onSetHidden = viewModel::setAppHidden,
                onBack = { showHiddenManager = false },
                modifier = modifier.fillMaxSize(),
            )
            return@CompositionLocalProvider
        }

        Surface(modifier = modifier.fillMaxSize(), color = palette.bg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
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

                ExpressiveActionRow(
                    label = stringResource(R.string.settings_set_default),
                    description = stringResource(
                        if (isDefaultLauncher) R.string.settings_default_current else R.string.settings_default_tap,
                    ),
                ) {
                    runCatching { setDefaultLauncher.launch(DefaultLauncher.requestIntent(context)) }
                }

                ExpressiveSectionTitle(stringResource(R.string.settings_gestures))
                SwitchRow(stringResource(R.string.settings_swipe_up_drawer), s.swipeUpForDrawer, viewModel::setSwipeUp)
                SwitchRow(stringResource(R.string.settings_swipe_down_notif), s.swipeDownForNotifications, viewModel::setSwipeDown)
                val leftSwipeLabel = allApps.firstOrNull { it.key == s.leftSwipeAppKey }?.label
                    ?: stringResource(R.string.settings_left_edge_none)
                ExpressiveActionRow(
                    label = stringResource(R.string.settings_left_edge),
                    description = leftSwipeLabel,
                ) { showLeftSwipePicker = true }

                ExpressiveSectionTitle(stringResource(R.string.settings_dock))
                SwitchRow(stringResource(R.string.settings_dock_show), s.dockEnabled, viewModel::setDockEnabled)
                StepperRow(stringResource(R.string.settings_dock_icons), s.dockColumns, 3, 7, viewModel::setDockColumns)
                SwitchRow(stringResource(R.string.settings_show_labels), s.showDockLabels, viewModel::setShowDockLabels)
                SliderRow(stringResource(R.string.settings_dock_bg), s.dockBackgroundOpacity, viewModel::setDockBackgroundOpacity)
                DockPreview(opacity = s.dockBackgroundOpacity, icon = previewIcon)

                ExpressiveSectionTitle(stringResource(R.string.settings_drawer))
                StepperRow(stringResource(R.string.settings_columns), s.drawerColumns, 3, 7, viewModel::setDrawerColumns)
                SwitchRow(stringResource(R.string.settings_show_labels), s.showDrawerLabels, viewModel::setShowDrawerLabels)
                SwitchRow(stringResource(R.string.settings_drawer_search), s.showDrawerSearch, viewModel::setShowDrawerSearch)
                SwitchRow(stringResource(R.string.settings_frequent_apps), s.showFrequentApps, viewModel::setShowFrequentApps)
                ExpressiveActionRow(
                    label = stringResource(R.string.settings_hidden_apps),
                    description = stringResource(R.string.settings_hidden_apps_desc, hiddenKeys.size),
                ) { showHiddenManager = true }
                val newFolderName = stringResource(R.string.drawer_folder_default)
                val folderCreatedMsg = stringResource(R.string.settings_drawer_folder_created)
                ExpressiveActionRow(
                    label = stringResource(R.string.settings_new_drawer_folder),
                    description = stringResource(R.string.settings_new_drawer_folder_desc),
                    trailingIcon = LauncherIcons.Add,
                ) {
                    viewModel.createDrawerFolder(newFolderName)
                    android.widget.Toast.makeText(context, folderCreatedMsg, android.widget.Toast.LENGTH_SHORT).show()
                }

                ExpressiveSectionTitle(stringResource(R.string.settings_home))
                StepperRow(stringResource(R.string.settings_columns), s.homeColumns, 3, 7, viewModel::setHomeColumns)
                SwitchRow(stringResource(R.string.settings_show_labels), s.showHomeLabels, viewModel::setShowHomeLabels)
                SwitchRow(stringResource(R.string.settings_page_indicator), s.showPageIndicator, viewModel::setShowPageIndicator)
                SwitchRow(stringResource(R.string.settings_lock_desktop), s.desktopLocked, viewModel::setDesktopLocked)
                SwitchRow(
                    stringResource(R.string.settings_hide_status_bar),
                    s.hideSystemStatusBar,
                    viewModel::setHideSystemStatusBar,
                )
                StatusBarToggle(enabled = s.showStatusBar, onSetEnabled = viewModel::setShowStatusBar)
                if (s.showStatusBar) {
                    SliderRow(
                        label = stringResource(R.string.settings_status_bar_scrim),
                        value = s.statusBarScrimOpacity,
                        onValueChange = viewModel::setStatusBarScrimOpacity,
                    )
                }

                ExpressiveSectionTitle(stringResource(R.string.settings_icons))
                SliderRow(
                    label = stringResource(R.string.settings_label_size),
                    value = s.appLabelTextScale,
                    onValueChange = viewModel::setAppLabelTextScale,
                    valueRange = 0.8f..1.6f,
                )
                LabelColorRow(selected = s.appLabelColor, onPick = viewModel::setAppLabelColor)
                // Themed (monochrome) icons need the adaptive-icon monochrome API (Android 13+).
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    SwitchRow(
                        stringResource(R.string.settings_themed_icons),
                        s.useThemedIcons,
                        viewModel::setUseThemedIcons,
                    )
                }

                ExpressiveSectionTitle(stringResource(R.string.settings_notifications))
                SwitchRow(stringResource(R.string.settings_notif_dots), s.showNotificationDots, viewModel::setShowNotificationDots)
                SwitchRow(stringResource(R.string.settings_notif_count), s.notificationDotCount, viewModel::setNotificationDotCount)
                SliderRow(
                    label = stringResource(R.string.settings_notif_size),
                    value = s.notificationDotScale,
                    onValueChange = viewModel::setNotificationDotScale,
                    valueRange = 0.6f..1.8f,
                )
                BadgePreview(
                    icon = previewIcon,
                    showDots = s.showNotificationDots,
                    showCount = s.notificationDotCount,
                    scale = s.notificationDotScale,
                )
                ExpressiveActionRow(
                    label = stringResource(R.string.settings_notif_access),
                    description = stringResource(R.string.settings_notif_access_desc),
                ) {
                    openNotificationAccess(context)
                }

                ExpressiveSectionTitle(stringResource(R.string.settings_search))
                ContactsSearchToggle(
                    enabled = s.searchContacts,
                    onSetEnabled = viewModel::setSearchContacts,
                )

                ExpressiveSectionTitle(stringResource(R.string.settings_backup))
                ExpressiveActionRow(
                    label = stringResource(R.string.settings_backup),
                    description = "",
                ) { showBackup = true }

                UpdateSection()
            }
        }

        if (showLeftSwipePicker) {
            LeftSwipeAppPicker(
                apps = allApps,
                onPick = { key ->
                    viewModel.setLeftSwipeAppKey(key)
                    showLeftSwipePicker = false
                },
                onDismiss = { showLeftSwipePicker = false },
            )
        }
    }
}

/** Single-select picker for the left-edge swipe app: a "None" entry on top, then every app. */
@Composable
private fun LeftSwipeAppPicker(
    apps: List<AppItem>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalExpressivePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceHi,
        title = { Text(stringResource(R.string.settings_left_edge_pick), color = p.text) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(null) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_left_edge_none), color = Accent, fontSize = 16.sp)
                }
                apps.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(app.key) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(appItem = app, labelColor = p.text, showLabel = false, iconSize = 32.dp)
                        Text(app.label, modifier = Modifier.padding(start = 12.dp), color = p.text)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close), color = Accent) }
        },
    )
}

/** Opens the system notification-access screen (deep-linked to this app when supported). */
private fun openNotificationAccess(context: android.content.Context) {
    val component = ComponentName(
        context.packageName,
        "org.arkikeskus.launcher.notifications.NotificationDotListenerService",
    )
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(detail) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
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

@Composable
private fun ContactsSearchToggle(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onSetEnabled(granted) // grant → on; deny → stays off
    }
    SwitchRow(stringResource(R.string.settings_search_contacts), enabled) { wantOn ->
        if (!wantOn) {
            onSetEnabled(false)
        } else {
            val has = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
            if (has) onSetEnabled(true) else launcher.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }
}

@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = LocalExpressivePalette.current.text, fontSize = 16.sp)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val p = LocalExpressivePalette.current
    ExpressiveCard {
        RowLabel(label, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                checkedThumbColor = Color.White,
                checkedBorderColor = Accent,
                uncheckedTrackColor = p.trackOff,
                uncheckedThumbColor = p.thumbOff,
                uncheckedBorderColor = p.trackOff,
            ),
        )
    }
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, onValueChange: (Int) -> Unit) {
    val p = LocalExpressivePalette.current
    ExpressiveCard {
        RowLabel(label, Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepButton(LauncherIcons.Remove) { if (value > min) onValueChange(value - 1) }
            Text(
                text = "$value",
                modifier = Modifier.widthIn(min = 26.dp),
                textAlign = TextAlign.Center,
                color = p.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            StepButton(LauncherIcons.Add) { if (value < max) onValueChange(value + 1) }
        }
    }
}

@Composable
private fun StepButton(@DrawableRes icon: Int, onClick: () -> Unit) {
    val p = LocalExpressivePalette.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(p.btn)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(label)
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = p.trackOff,
                ),
            )
        }
    }
}

/** Row of preset label-color swatches for the home-surface app labels (home/dock/folders). The
 *  selected swatch gets an accent ring; every swatch has a faint outline so white reads on the card. */
@Composable
private fun LabelColorRow(selected: Int, onPick: (Int) -> Unit) {
    val p = LocalExpressivePalette.current
    val swatches = remember {
        listOf(
            0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, 0xFFEF5350,
            0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC,
        ).map { it.toInt() }
    }
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_label_color))
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                swatches.forEach { c ->
                    val isSel = c == selected
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(
                                width = if (isSel) 3.dp else 1.dp,
                                color = if (isSel) Accent else p.faint,
                                shape = CircleShape,
                            )
                            .clickable { onPick(c) },
                    )
                }
            }
        }
    }
}

/**
 * Full-screen manager listing every app with a switch — toggle to hide/show it in the app drawer.
 * (Apps can also be hidden via the drawer long-press, but only restored here.)
 */
@Composable
private fun HiddenAppsManager(
    apps: List<AppItem>,
    hiddenKeys: Set<String>,
    onSetHidden: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalExpressivePalette.current
    BackHandler(onBack = onBack)
    Surface(modifier = modifier, color = p.bg) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Text(
                text = stringResource(R.string.settings_hidden_apps),
                color = p.text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = apps, key = { it.key }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(
                            appItem = app,
                            labelColor = p.text,
                            showLabel = false,
                            iconSize = 40.dp,
                        )
                        Text(
                            text = app.label,
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                            color = p.text,
                            fontSize = 16.sp,
                        )
                        Switch(
                            checked = app.key in hiddenKeys,
                            onCheckedChange = { onSetHidden(app.key, it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Accent,
                                checkedThumbColor = Color.White,
                                checkedBorderColor = Accent,
                                uncheckedTrackColor = p.trackOff,
                                uncheckedThumbColor = p.thumbOff,
                                uncheckedBorderColor = p.trackOff,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** The launcher's own app icon, for use as the settings preview sample. */
@Composable
private fun rememberLauncherIconBitmap(): ImageBitmap? {
    val context = LocalContext.current
    return remember {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val size = 144
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}

/** Live preview of the dock at the chosen background opacity, over a neutral backdrop so the dark
 *  dock scrim stays visible on both light and dark themes. */
@Composable
private fun DockPreview(opacity: Float, icon: ImageBitmap?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(Color(0xFFB9C3D4), RoundedCornerShape(22.dp))
            .padding(8.dp),
    ) {
        Surface(
            color = Color.Black.copy(alpha = opacity),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                repeat(4) {
                    if (icon != null) {
                        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
                    } else {
                        Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.6f), CircleShape))
                    }
                }
            }
        }
    }
}

/** Live preview of the notification badge on a sample icon (the launcher's own). */
@Composable
private fun BadgePreview(icon: ImageBitmap?, showDots: Boolean, showCount: Boolean, scale: Float) {
    Box(
        modifier = Modifier.padding(bottom = 10.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(56.dp))
        } else {
            Box(Modifier.size(56.dp).background(Accent, RoundedCornerShape(14.dp)))
        }
        if (showDots) {
            NotificationBadge(count = 5, showCount = showCount, scale = scale)
        }
    }
}
