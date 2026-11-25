package org.arkikeskus.launcher.feature.home

import android.icu.text.BreakIterator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.arkikeskus.launcher.designsystem.theme.currentBasePalette
import org.arkikeskus.launcher.model.HomeProfile
import org.arkikeskus.launcher.model.pictureOutline
import org.arkikeskus.launcher.ui.IconMenuItem
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.component.AppLabel
import org.arkikeskus.launcher.ui.component.LocalIconShape
import org.arkikeskus.launcher.ui.shownProfileName

@Composable
internal fun homeWidgetAndProfileOptions(
    locked: Boolean, profileCount: Int, onWidgets: () -> Unit, onProfiles: () -> Unit,
): List<IconMenuItem> = buildList {
    if (!locked) add(IconMenuItem(LauncherIcons.Widgets, stringResource(R.string.home_options_widgets), onWidgets))
    if (profileCount >= 2) add(IconMenuItem(LauncherIcons.Person, stringResource(R.string.home_profiles), onProfiles))
}

@Composable
internal fun ProfileChoices(
    profiles: List<HomeProfile>, active: Long, adding: Boolean, onPick: (Long) -> Unit,
    onManage: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (adding) R.string.profile_switch_question else R.string.home_profiles)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (profile in profiles) {
                    val current = profile.id == active
                    if (adding && current) continue
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !current, role = Role.Button) { onPick(profile.id) }
                            .heightIn(min = 48.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(shownProfileName(LocalResources.current, profile.name), Modifier.weight(1f))
                        if (current) Text(stringResource(R.string.home_profile_in_use), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (!adding) TextButton(onClick = onManage) { Text(stringResource(R.string.home_manage_profiles)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun ProfileSwitchIcon(
    target: HomeProfile?, modifier: Modifier, iconSize: Dp, showLabel: Boolean, labelColor: Color,
    onSwitch: (Long) -> Unit, onMissing: () -> Unit,
) {
    val resources = LocalResources.current
    val label = target?.let { shownProfileName(resources, it.name) } ?: stringResource(R.string.profile_switch_name)
    val description = if (target == null) stringResource(R.string.profile_switch_missing)
        else stringResource(R.string.profile_switch_to, label)
    ProfileMonogram(label, iconSize, showLabel, labelColor,
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).alpha(if (target == null) 0.45f else 1f)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) { if (target == null) onMissing() else onSwitch(target.id) })
}

@Composable
internal fun ProfileMonogram(label: String, iconSize: Dp, showLabel: Boolean, labelColor: Color, modifier: Modifier) {
    val palette = currentBasePalette()
    val shape = LocalIconShape.current
    val locale = LocalResources.current.configuration.locales[0]
    val initial = remember(label, locale) {
        val upper = label.uppercase(locale)
        val chars = BreakIterator.getCharacterInstance(locale).apply { setText(upper) }
        upper.substring(0, chars.next().coerceAtLeast(0))
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(iconSize).drawWithCache {
            val outline = pictureOutline(shape, size.minDimension.roundToInt().coerceAtLeast(1)).asComposePath()
            onDrawBehind { drawPath(outline, palette.fill) }
        }, contentAlignment = Alignment.Center) {
            Text(initial, color = palette.onFill, fontSize = with(LocalDensity.current) {
                (iconSize * 0.48f).toSp()
            }, maxLines = 1)
        }
        if (showLabel) AppLabel(label, labelColor, reserveLines = true)
    }
}
