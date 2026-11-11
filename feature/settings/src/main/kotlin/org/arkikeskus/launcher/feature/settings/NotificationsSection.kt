package org.arkikeskus.launcher.feature.settings

import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.component.NotificationBadge
import org.arkikeskus.launcher.ui.expressive.Accent

/** Settings > Notifications: the badges on the icons, with a preview. */
@Composable
internal fun NotificationsRows(s: LauncherSettings, actions: SettingsActions) {
    val percentText = percentReadout()
    val previewIcon = rememberLauncherIconBitmap()
    SettingRow("N1") {
        SwitchRow(stringResource(R.string.settings_notif_dots), s.showNotificationDots, actions::setShowNotificationDots)
    }
    SettingRow("N2") {
        SwitchRow(stringResource(R.string.settings_notif_count), s.notificationDotCount, actions::setNotificationDotCount)
    }
    SettingRow("N3") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_notif_size),
            value = s.notificationDotScale,
            onValueChange = actions::setNotificationDotScale,
            valueRange = 0.6f..1.8f,
            valueText = percentText,
        )
    }
    BadgePreview(
        icon = previewIcon,
        showDots = s.showNotificationDots,
        showCount = s.notificationDotCount,
        scale = s.notificationDotScale,
    )
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

