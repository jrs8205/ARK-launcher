package org.arkikeskus.launcher.feature.appdrawer

import android.app.ActivityOptions
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.data.PrivateSettingsTarget
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.ExpressiveCard
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.expressive.expressiveButtonColors

/**
 * The private space as the drawer's Private page shows it; the drawer has one only while the Private tab
 * exists. [transitioning] is true from a lock or unlock request until Android reports the change;
 * [canInstall] and [canOpenSettings] say whether the Install tile and the settings button lead anywhere.
 */
data class PrivateSpaceUi(
    val locked: Boolean,
    val transitioning: Boolean = false,
    val canInstall: Boolean = false,
    val canOpenSettings: Boolean = false,
)

/** The mark on the Unlock button while Android's answer is on its way (a test finds it by this tag). */
internal const val PRIVATE_UNLOCK_WORKING_TAG = "private-unlock-working"

private val PrivateButtonMinHeight = 48.dp
private val PrivateButtonsMargin = 16.dp

/**
 * The Private page: the locked card, or the private apps' [grid] with the floating Lock button and, when it
 * leads somewhere, the settings button over it. As on the Work page the grid is handed the buttons' MEASURED
 * height plus their margin as bottom padding, so its last row is never left under them.
 */
@Composable
internal fun PrivatePage(
    space: PrivateSpaceUi,
    onSetLocked: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    grid: @Composable (bottomPadding: Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (space.locked) {
            PrivateLockedCard(working = space.transitioning, onUnlock = { onSetLocked(false) })
        } else {
            val density = LocalDensity.current
            var buttonsHeightPx by remember { mutableIntStateOf(0) }
            val reserved = if (buttonsHeightPx == 0) {
                PrivateButtonMinHeight + PrivateButtonsMargin // until measured
            } else {
                with(density) { buttonsHeightPx.toDp() } + PrivateButtonsMargin
            }
            grid(reserved)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = PrivateButtonsMargin)
                    .onSizeChanged { buttonsHeightPx = it.height },
            ) {
                Button(
                    onClick = { onSetLocked(true) },
                    enabled = !space.transitioning,
                    shape = RoundedCornerShape(24.dp),
                    colors = expressiveButtonColors(),
                    modifier = Modifier.heightIn(min = PrivateButtonMinHeight),
                ) {
                    Icon(painter = painterResource(LauncherIcons.Lock), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.private_space_lock))
                }
                if (space.canOpenSettings) {
                    val description = stringResource(R.string.private_space_settings)
                    Button(
                        onClick = onOpenSettings,
                        shape = CircleShape,
                        colors = expressiveButtonColors(),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(PrivateButtonMinHeight).semantics { contentDescription = description },
                    ) {
                        Icon(painter = painterResource(LauncherIcons.Settings), contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

/**
 * What the Private page shows while the space is locked: the lock mark, one line, and Unlock. Unlock asks
 * Android, which shows its own prompt; while the answer is on its way ([working]) the button shows a ring
 * and takes no tap. Fills its page so the card sits in the middle.
 *
 * The page is a scroll container although the card fits, like the paused Work page: the drawer's
 * pull-to-close is a nested-scroll parent, and only a scrollable child hands it a downward drag.
 */
@Composable
internal fun PrivateLockedCard(working: Boolean, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalExpressivePalette.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val pageHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = pageHeight)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            ExpressiveCard {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(LauncherIcons.Lock),
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.private_space_locked_title),
                        color = palette.text,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onUnlock,
                        enabled = !working,
                        shape = RoundedCornerShape(24.dp),
                        colors = expressiveButtonColors(),
                    ) {
                        if (working) {
                            CircularProgressIndicator(
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp).testTag(PRIVATE_UNLOCK_WORKING_TAG),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.private_space_unlock))
                    }
                }
            }
        }
    }
}

/** The tile after the private apps: opens an app store inside the private space. */
internal fun LazyGridScope.privateInstallTile(iconSize: Dp, showLabel: Boolean, onInstall: () -> Unit) {
    item(key = "private-install", contentType = "private-install") { PrivateInstallTile(iconSize, showLabel, onInstall) }
}

/** Shaped like an app tile of the grid it ends: a round plus in the container colour, "Install" under it. */
@Composable
private fun PrivateInstallTile(iconSize: Dp, showLabel: Boolean, onInstall: () -> Unit) {
    val palette = LocalExpressivePalette.current
    val label = stringResource(R.string.private_space_install)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onInstall)
            .then(if (showLabel) Modifier else Modifier.semantics { contentDescription = label })
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Box(
            modifier = Modifier.size(iconSize).clip(CircleShape).background(palette.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(LauncherIcons.Add),
                contentDescription = null,
                tint = palette.onContainer,
                modifier = Modifier.size(iconSize * 0.45f),
            )
        }
        if (showLabel) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                color = drawerLabelColor(LocalDrawerLabelColor.current, MaterialTheme.colorScheme.onSurface),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Starts one of the system's own senders (the private space's store or settings), as the system asks a sender
 *  to from Android 14: with the leave to start an activity. A sender that cannot be started does nothing. */
internal fun startSystemSender(context: Context, sender: IntentSender) {
    runCatching {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        context.startIntentSender(sender, null, 0, 0, 0, options.toBundle())
    }
}

/** Opens what the Private tab's settings button leads to; a target that cannot be opened does nothing. */
internal fun openPrivateSettings(context: Context, target: PrivateSettingsTarget?) {
    when (target) {
        is PrivateSettingsTarget.Sender -> startSystemSender(context, target.sender)
        is PrivateSettingsTarget.Plain -> runCatching { context.startActivity(target.intent) }
        null -> Unit
    }
}
