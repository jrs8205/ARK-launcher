package org.arkikeskus.launcher.feature.settings

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.NotificationBadge

// --- "Version C" settings theme ------------------------------------------------------------------
// A self-contained, expressive look for the settings page (warm-orange accent, each row its own
// rounded card). It deliberately does NOT use the app-wide Arkikeskus (blue) theme — the colours are
// applied explicitly here so the rest of the launcher keeps its identity. Tokens from the UI spec.

private val Accent = Color(0xFFE2714A)

private data class SettingsPalette(
    val bg: Color,
    val surfaceHi: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val trackOff: Color,
    val thumbOff: Color,
    val btn: Color,
    val shadow: Dp,
)

private val DarkPalette = SettingsPalette(
    bg = Color(0xFF0C0E13),
    surfaceHi = Color(0xFF1D222B),
    text = Color(0xFFE9EBEE),
    dim = Color(0xFF9AA1AB),
    faint = Color(0xFF6C7079),
    trackOff = Color(0xFF3A404A),
    thumbOff = Color(0xFFAEB4BD),
    btn = Color.White.copy(alpha = 0.07f),
    shadow = 0.dp,
)

private val LightPalette = SettingsPalette(
    bg = Color(0xFFECEEF2),
    surfaceHi = Color(0xFFF4F6F9),
    text = Color(0xFF1A1C1E),
    dim = Color(0xFF5B616A),
    faint = Color(0xFF9499A1),
    trackOff = Color(0xFFC8CCD3),
    thumbOff = Color(0xFFFFFFFF),
    btn = Color.Black.copy(alpha = 0.05f),
    shadow = 1.dp,
)

private val LocalSettingsPalette = staticCompositionLocalOf { LightPalette }

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
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette

    CompositionLocalProvider(LocalSettingsPalette provides palette) {
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

                SectionTitle(stringResource(R.string.settings_gestures))
                SwitchRow(stringResource(R.string.settings_swipe_up_drawer), s.swipeUpForDrawer, viewModel::setSwipeUp)
                SwitchRow(stringResource(R.string.settings_swipe_down_notif), s.swipeDownForNotifications, viewModel::setSwipeDown)

                SectionTitle(stringResource(R.string.settings_dock))
                SwitchRow(stringResource(R.string.settings_dock_show), s.dockEnabled, viewModel::setDockEnabled)
                StepperRow(stringResource(R.string.settings_dock_icons), s.dockColumns, 3, 7, viewModel::setDockColumns)
                SwitchRow(stringResource(R.string.settings_show_labels), s.showDockLabels, viewModel::setShowDockLabels)
                SliderRow(stringResource(R.string.settings_dock_bg), s.dockBackgroundOpacity, viewModel::setDockBackgroundOpacity)
                DockPreview(opacity = s.dockBackgroundOpacity, icon = previewIcon)

                SectionTitle(stringResource(R.string.settings_drawer))
                StepperRow(stringResource(R.string.settings_columns), s.drawerColumns, 3, 7, viewModel::setDrawerColumns)
                SwitchRow(stringResource(R.string.settings_show_labels), s.showDrawerLabels, viewModel::setShowDrawerLabels)
                SwitchRow(stringResource(R.string.settings_drawer_search), s.showDrawerSearch, viewModel::setShowDrawerSearch)
                ActionRow(
                    label = stringResource(R.string.settings_hidden_apps),
                    description = stringResource(R.string.settings_hidden_apps_desc, hiddenKeys.size),
                ) { showHiddenManager = true }
                val newFolderName = stringResource(R.string.drawer_folder_default)
                val folderCreatedMsg = stringResource(R.string.settings_new_drawer_folder)
                ActionRow(
                    label = stringResource(R.string.settings_new_drawer_folder),
                    description = stringResource(R.string.settings_new_drawer_folder_desc),
                ) {
                    viewModel.createDrawerFolder(newFolderName)
                    android.widget.Toast.makeText(context, folderCreatedMsg, android.widget.Toast.LENGTH_SHORT).show()
                }

                SectionTitle(stringResource(R.string.settings_home))
                StepperRow(stringResource(R.string.settings_columns), s.homeColumns, 3, 7, viewModel::setHomeColumns)
                SwitchRow(stringResource(R.string.settings_show_labels), s.showHomeLabels, viewModel::setShowHomeLabels)
                SwitchRow(stringResource(R.string.settings_page_indicator), s.showPageIndicator, viewModel::setShowPageIndicator)

                SectionTitle(stringResource(R.string.settings_notifications))
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
                ActionRow(
                    label = stringResource(R.string.settings_notif_access),
                    description = stringResource(R.string.settings_notif_access_desc),
                ) {
                    openNotificationAccess(context)
                }
            }
        }
    }
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

@Composable
private fun SectionTitle(text: String) {
    val p = LocalSettingsPalette.current
    Text(
        text = text,
        color = p.text,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        modifier = Modifier.padding(top = 26.dp, start = 4.dp, bottom = 12.dp),
    )
}

/** A single rounded setting card (the standard inline-control row). */
@Composable
private fun SettingCard(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val p = LocalSettingsPalette.current
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = LocalSettingsPalette.current.text, fontSize = 16.sp)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val p = LocalSettingsPalette.current
    SettingCard {
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
    val p = LocalSettingsPalette.current
    SettingCard {
        RowLabel(label, Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepButton("−") { if (value > min) onValueChange(value - 1) }
            Text(
                text = "$value",
                modifier = Modifier.widthIn(min = 26.dp),
                textAlign = TextAlign.Center,
                color = p.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            StepButton("+") { if (value < max) onValueChange(value + 1) }
        }
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    val p = LocalSettingsPalette.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(p.btn)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val p = LocalSettingsPalette.current
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

/** A link-style card: accent title + description, with a trailing chevron. */
@Composable
private fun ActionRow(label: String, description: String, onClick: () -> Unit) {
    val p = LocalSettingsPalette.current
    SettingCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(description, color = p.dim, fontSize = 12.5.sp)
        }
        Text("›", color = p.faint, fontSize = 24.sp)
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
    val p = LocalSettingsPalette.current
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
