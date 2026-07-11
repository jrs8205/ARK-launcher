package org.arkikeskus.launcher.feature.home

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.NotificationWidgetLayout
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.StatusNotification
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.NotificationBadge
import javax.inject.Inject

@HiltViewModel
class NotificationsWidgetViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val badgeRepository: NotificationBadgeRepository,
    private val appRepository: AppRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** One widget slot: an app's newest notification + the resolved launcher app (null when the
     *  package has no launcher activity — rendered from the notification's small icon instead). */
    data class Slot(val notification: StatusNotification, val app: AppItem?)

    /** Whether our notification listener is enabled — re-checked on home resume (granting happens
     *  in the system settings, so there is no result callback to observe). */
    val hasAccess = MutableStateFlow(isAccessGranted())

    fun refresh() {
        hasAccess.value = isAccessGranted()
    }

    private fun isAccessGranted(): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    val countStyle: StateFlow<String> = settingsRepository.settings
        .map { it.notificationWidgetCountStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherSettings.COUNT_NUMBER)

    val slots: StateFlow<List<Slot>> = combine(badgeRepository.icons, appRepository.apps) { notifs, apps ->
        val byBadgeKey = apps.associateBy { it.badgeKey }
        notifs.map { Slot(it, byBadgeKey["${it.packageName}/${it.userSerial}"]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tap = the notification's own action (shade parity: auto-cancel dismisses it). Falls back
     *  through app launch → app notification settings → app details so a visible icon always does
     *  something predictable, even for a system notification with no action and no launcher activity. */
    fun open(slot: Slot) {
        val pi = slot.notification.contentIntent
        val sent = pi != null && runCatching {
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ no longer grants the sender's foreground privileges implicitly; the
                // launcher is in the foreground on tap, so the grant is ours to give.
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
            }
            pi.send(context, 0, null, null, null, null, options.toBundle())
        }.isSuccess
        if (sent) {
            if (slot.notification.autoCancel) badgeRepository.cancelNotification(slot.notification.key)
            return
        }
        val app = slot.app
        if (app != null && appRepository.launch(app).isSuccess) return
        openAppNotificationSettings(slot.notification.packageName)
    }

    /** Last-resort open: the app's own notification settings, then its app-details page — so a
     *  system/service notification with no action and no launcher activity is never a dead tap. */
    private fun openAppNotificationSettings(pkg: String) {
        val channelSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(channelSettings); true }.getOrDefault(false)) return
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(details) }
    }
}

/**
 * The built-in notifications widget: the active notifications as one colored app icon per app
 * (newest first), a "+N" chip when they don't fit, and a per-setting count indicator. Fully
 * invisible when there is nothing to show (the grid footprint and long-press edit remain).
 */
@Composable
fun NotificationsWidget(
    modifier: Modifier = Modifier,
    viewModel: NotificationsWidgetViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val hasAccess by viewModel.hasAccess.collectAsStateWithLifecycle()
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val countStyle by viewModel.countStyle.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f)
    // Taps must not show a ripple box over the wallpaper (same idiom as the smartspace widget).
    val noIndication = remember { MutableInteractionSource() }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // Icons scale with the footprint HEIGHT (a 1-row widget stays compact, taller spans grow);
        // the width decides how many icons fit.
        val scale = (maxHeight / 96.dp).coerceIn(0.8f, 1.6f)
        val iconSize = (40 * scale).dp
        val spacing = (10 * scale).dp
        when {
            !hasAccess -> Text(
                text = stringResource(R.string.notifications_widget_allow_access),
                color = Color.White.copy(alpha = 0.85f),
                style = TextStyle(fontSize = (14 * scale).sp, shadow = shadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.30f), RoundedCornerShape((18 * scale).dp))
                    .clickable(interactionSource = noIndication, indication = null) {
                        openNotificationListenerSettings(context)
                    }
                    .padding(horizontal = (16 * scale).dp, vertical = (8 * scale).dp),
            )
            slots.isEmpty() -> Unit // No notifications → nothing renders (user choice).
            else -> {
                // Slots the width fits after the card's own padding; the chip shares the last slot.
                val maxSlots = maxOf(1, ((maxWidth - (24 * scale).dp + spacing) / (iconSize + spacing)).toInt())
                val (shown, overflow) = NotificationWidgetLayout.select(slots, maxSlots)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.30f), RoundedCornerShape((18 * scale).dp))
                        .padding(horizontal = (12 * scale).dp, vertical = (8 * scale).dp),
                ) {
                    shown.forEach { slot ->
                        NotificationSlot(slot, iconSize, countStyle, scale, noIndication) {
                            viewModel.open(slot)
                        }
                    }
                    if (overflow > 0) {
                        Text(
                            text = "+$overflow",
                            color = Color.White,
                            style = TextStyle(
                                fontSize = (14 * scale).sp,
                                fontWeight = FontWeight.Medium,
                                shadow = shadow,
                            ),
                            maxLines = 1,
                            // The chip opens the shade — the hidden notifications are one tap away
                            // instead of unreachable from the widget.
                            modifier = Modifier.clickable(
                                interactionSource = noIndication,
                                indication = null,
                            ) { NotificationShade.expand(context) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationSlot(
    slot: NotificationsWidgetViewModel.Slot,
    iconSize: Dp,
    countStyle: String,
    scale: Float,
    interaction: MutableInteractionSource,
    onOpen: () -> Unit,
) {
    val badgeCount = if (countStyle == LauncherSettings.COUNT_NONE) 0 else slot.notification.count
    val showCount = countStyle == LauncherSettings.COUNT_NUMBER
    Box(
        modifier = Modifier.clickable(interactionSource = interaction, indication = null, onClick = onOpen),
    ) {
        val app = slot.app
        if (app != null) {
            AppIcon(
                appItem = app,
                labelColor = Color.White,
                showLabel = false,
                iconSize = iconSize,
                badgeCount = badgeCount,
                badgeShowCount = showCount,
                badgeScale = scale,
            )
        } else {
            // The package has no launcher activity — fall back to the notification's own small
            // icon, tinted white like the status bar renders it.
            val bitmap = rememberNotifSmallIcon(slot.notification, iconSize)
            Box(contentAlignment = Alignment.TopEnd) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = slot.notification.packageName,
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier.size(iconSize),
                    )
                } else {
                    // Rasterization failed — a generic bell keeps the slot tappable instead of an
                    // invisible dead zone that still consumes a slot.
                    Icon(
                        painter = painterResource(R.drawable.ic_notification_generic),
                        contentDescription = slot.notification.packageName,
                        tint = Color.White,
                        modifier = Modifier.size(iconSize),
                    )
                }
                NotificationBadge(count = badgeCount, showCount = showCount, scale = scale)
            }
        }
    }
}

/** Rasterises the notification's small icon at the slot size; null on any failure (slot skipped). */
@Composable
private fun rememberNotifSmallIcon(n: StatusNotification, size: Dp): ImageBitmap? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    return remember(n.key, n.postTime, px) {
        runCatching { n.icon.loadDrawable(context)?.toBitmap(width = px, height = px)?.asImageBitmap() }.getOrNull()
    }
}

/** Opens the system notification-access screen, deep-linked to this app when supported. */
internal fun openNotificationListenerSettings(context: Context) {
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
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
