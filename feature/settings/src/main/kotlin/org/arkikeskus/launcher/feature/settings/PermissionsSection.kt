package org.arkikeskus.launcher.feature.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow

/** Settings > Permissions: every permission the launcher uses, with a live granted or not-granted status. */
@Composable
internal fun PermissionsRows() {
    val context = LocalContext.current
    // Notification access is granted/revoked in the SYSTEM settings (plain startActivity, no result
    // contract) — re-check on every resume so the row shows the fresh state when the user comes back.
    var notifAccessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    LifecycleResumeEffect(Unit) {
        notifAccessGranted = isNotificationAccessGranted(context)
        onPauseOrDispose { }
    }
    SettingRow("P1") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_notif_access),
            description = stringResource(
                if (notifAccessGranted) {
                    R.string.settings_notif_access_granted
                } else {
                    R.string.settings_notif_access_not_granted
                },
            ),
        ) {
            openNotificationAccess(context)
        }
    }
    SettingRow("P2") {
        PermissionRow(
            label = stringResource(R.string.settings_permission_calendar),
            permission = android.Manifest.permission.READ_CALENDAR,
        )
    }
    SettingRow("P3") {
        PermissionRow(
            label = stringResource(R.string.settings_permission_contacts),
            permission = android.Manifest.permission.READ_CONTACTS,
        )
    }
    SettingRow("P4") {
        PermissionRow(
            label = stringResource(R.string.settings_permission_phone),
            permission = android.Manifest.permission.READ_PHONE_STATE,
        )
    }
    SettingRow("P5") {
        PermissionRow(
            label = stringResource(R.string.settings_permission_location),
            permission = android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}

/** One runtime permission's live status; tap requests it. When Android suppresses the dialog
 *  (permanently denied), the only remaining path is the app's system settings page — opened on a
 *  dialog-less denial. A dialog the user merely backed out of answers the same (no grant, no
 *  rationale), so the first such answer counts as a dismissal; only a repeat opens the system
 *  page. Re-checked on resume so a grant/revoke made in the system settings shows the moment the
 *  user comes back (the v0.6.7 notif-access pattern). */
@Composable
internal fun PermissionRow(label: String, permission: String) {
    // Whether this row already asked once: the second dialog-less "no" is the permanent one.
    var asked by rememberSaveable(permission) { mutableStateOf(false) }
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    LifecycleResumeEffect(permission) {
        granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        val activity = context.findActivity()
        if (!result && asked && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        ) {
            openAppDetailsSettings(context)
        }
        asked = true
    }
    ExpressiveActionRow(
        label = label,
        description = stringResource(
            if (granted) R.string.settings_permission_granted else R.string.settings_permission_not_granted,
        ),
    ) {
        if (!granted) launcher.launch(permission)
    }
}

/** Unwraps the Activity behind a Compose LocalContext (a ContextWrapper chain). */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The app's system details page — where a permanently denied permission can still be granted. */
private fun openAppDetailsSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** True when our notification listener has been granted notification access in the system settings. */
private fun isNotificationAccessGranted(context: android.content.Context): Boolean =
    runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

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
