package org.arkikeskus.launcher.feature.home

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.arkikeskus.launcher.ui.DefaultLauncher
import org.arkikeskus.launcher.ui.LauncherIcons

/** The Arkikeskus brand colors the aurora blobs drift in (fixed — independent of the theme). */
private val AURORA_BASE = Color(0xFF111318)
private val AURORA_BLUE = Color(0xFF1B53C0)
private val AURORA_GREEN = Color(0xFF1E7D43)
private val AURORA_AMBER = Color(0xFFB5530F)

/**
 * First-run intro shown once on a fresh install, over the whole home screen: welcome +
 * set-as-default, the core gestures, and a centralized permission step. [onFinish] fires on
 * "Valmis" and on "Ohita" (either way it is never shown again); [onContactsGranted] lets the
 * caller flip the contact-search setting on when READ_CONTACTS is granted here.
 */
@Composable
fun OnboardingFlow(
    onFinish: () -> Unit,
    onContactsGranted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page-- }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AURORA_BASE)
            // The intro covers a LIVE home screen — swallow every touch that no child claims so
            // nothing falls through to the dock/workspace below (user-found bug: dock apps opened
            // through the intro's empty areas).
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        AuroraBackground()
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.onboarding_skip), color = Color.White.copy(alpha = 0.8f))
                }
            }
            Crossfade(
                targetState = page,
                label = "onboarding-page",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { p ->
                when (p) {
                    0 -> WelcomePage()
                    1 -> GesturesPage()
                    else -> PermissionsPage(onContactsGranted)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A visible back button — the system back gesture is unreliable on a HOME activity.
                if (page > 0) {
                    TextButton(onClick = { page-- }) {
                        Text(stringResource(R.string.onboarding_back), color = Color.White.copy(alpha = 0.85f))
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    repeat(3) { i ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (i == page) Color.White else Color.White.copy(alpha = 0.35f),
                                    CircleShape,
                                ),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { if (page < 2) page++ else onFinish() },
                    colors = ButtonDefaults.buttonColors(containerColor = AURORA_BLUE),
                ) {
                    Text(
                        stringResource(if (page < 2) R.string.onboarding_next else R.string.onboarding_finish),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** Slowly drifting radial-gradient blobs in the brand colors — a calm, asset-free backdrop. */
@Composable
private fun AuroraBackground() {
    val transition = rememberInfiniteTransition(label = "aurora")
    fun drift(durationMs: Int) = infiniteRepeatable<Float>(
        animation = tween(durationMs, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
    )
    val t1 by transition.animateFloat(0f, 1f, drift(19_000), label = "t1")
    val t2 by transition.animateFloat(0f, 1f, drift(24_000), label = "t2")
    val t3 by transition.animateFloat(0f, 1f, drift(31_000), label = "t3")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.50f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }
        blob(AURORA_BLUE, w * (0.10f + 0.55f * t1), h * (0.08f + 0.22f * t2), w * 0.85f)
        blob(AURORA_GREEN, w * (0.90f - 0.55f * t2), h * (0.50f + 0.22f * t3), w * 0.75f)
        blob(AURORA_AMBER, w * (0.30f + 0.45f * t3), h * (0.95f - 0.25f * t1), w * 0.70f)
    }
}

@Composable
private fun WelcomePage() {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(DefaultLauncher.isDefault(context)) }
    val setDefault = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { isDefault = DefaultLauncher.isDefault(context) }
    LifecycleResumeEffect(Unit) {
        isDefault = DefaultLauncher.isDefault(context)
        onPauseOrDispose { }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_text),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (isDefault) {
            Text(
                text = stringResource(R.string.onboarding_default_done),
                color = AURORA_GREEN_LIGHT,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Button(
                onClick = { runCatching { setDefault.launch(DefaultLauncher.requestIntent(context)) } },
                colors = ButtonDefaults.buttonColors(containerColor = AURORA_BLUE),
            ) {
                Text(stringResource(R.string.onboarding_set_default), color = Color.White)
            }
        }
    }
}

private val AURORA_GREEN_LIGHT = Color(0xFF8FD89E)

@Composable
private fun GesturesPage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_gestures_title),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(24.dp))
        GestureRow(rotate = -90f, text = stringResource(R.string.onboarding_gesture_up))
        Spacer(Modifier.height(18.dp))
        GestureRow(rotate = 90f, text = stringResource(R.string.onboarding_gesture_down))
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_home_settings),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.onboarding_gesture_hold),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun GestureRow(rotate: Float, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(LauncherIcons.ChevronRight),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = rotate },
        )
        Spacer(Modifier.width(16.dp))
        Text(text = text, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun hasNotificationAccess(context: Context): Boolean = runCatching {
    context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
        ComponentName(context.packageName, NOTIFICATION_LISTENER_CLASS),
    )
}.getOrDefault(false)

/** The app module's listener; referenced by name because feature modules can't depend on :app. */
private const val NOTIFICATION_LISTENER_CLASS =
    "org.arkikeskus.launcher.notifications.NotificationDotListenerService"

private fun granted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionsPage(onContactsGranted: () -> Unit) {
    val context = LocalContext.current
    var notifAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var calendar by remember { mutableStateOf(granted(context, Manifest.permission.READ_CALENDAR)) }
    var contacts by remember { mutableStateOf(granted(context, Manifest.permission.READ_CONTACTS)) }
    var phone by remember { mutableStateOf(granted(context, Manifest.permission.READ_PHONE_STATE)) }
    fun refresh() {
        notifAccess = hasNotificationAccess(context)
        calendar = granted(context, Manifest.permission.READ_CALENDAR)
        contacts = granted(context, Manifest.permission.READ_CONTACTS)
        phone = granted(context, Manifest.permission.READ_PHONE_STATE)
    }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    fun openNotificationAccessSettings() {
        // Deep-link to OUR listener's toggle; fall back to the generic list.
        val cn = ComponentName(context.packageName, NOTIFICATION_LISTENER_CLASS)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
        runCatching { context.startActivity(detail) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
    }

    // "Grant all" must really mean ALL: notification access is a Settings screen, not a runtime
    // dialog, so it can't join the dialog chain — it opens right after the chain finishes instead.
    var openNotifAfterChain by remember { mutableStateOf(false) }
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.READ_CONTACTS] == true) onContactsGranted()
        refresh()
        if (openNotifAfterChain) {
            openNotifAfterChain = false
            if (!hasNotificationAccess(context)) openNotificationAccessSettings()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_perms_title),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_perms_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(20.dp))
        PermissionRow(
            title = stringResource(R.string.onboarding_perm_notif),
            description = stringResource(R.string.onboarding_perm_notif_desc),
            granted = notifAccess,
            onClick = { openNotificationAccessSettings() },
        )
        PermissionRow(
            title = stringResource(R.string.onboarding_perm_calendar),
            description = stringResource(R.string.onboarding_perm_calendar_desc),
            granted = calendar,
            onClick = { permissionsLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR)) },
        )
        PermissionRow(
            title = stringResource(R.string.onboarding_perm_contacts),
            description = stringResource(R.string.onboarding_perm_contacts_desc),
            granted = contacts,
            onClick = { permissionsLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
        )
        PermissionRow(
            title = stringResource(R.string.onboarding_perm_phone),
            description = stringResource(R.string.onboarding_perm_phone_desc),
            granted = phone,
            onClick = { permissionsLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) },
        )
        Spacer(Modifier.height(20.dp))
        if (!(calendar && contacts && phone && notifAccess)) {
            Button(
                onClick = {
                    openNotifAfterChain = true
                    permissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.READ_PHONE_STATE,
                        ),
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = AURORA_BLUE),
            ) {
                Text(stringResource(R.string.onboarding_grant_all), color = Color.White)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, description: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(enabled = !granted, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = if (granted) stringResource(R.string.onboarding_granted) else stringResource(R.string.onboarding_grant),
            color = if (granted) AURORA_GREEN_LIGHT else Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
