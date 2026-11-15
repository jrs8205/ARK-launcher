package org.arkikeskus.launcher.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.designsystem.theme.currentBasePalette
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.expressive.paletteSeed
import org.arkikeskus.launcher.ui.expressive.expressiveCardShadow
import org.arkikeskus.launcher.ui.expressive.expressiveCardShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import org.arkikeskus.launcher.ui.expressive.pressSpring

internal const val BASE_COLOUR_CARD_TAG = "base-colour-card"

/** The tag one base-colour choice carries on the Appearance page. */
internal fun baseColourTag(key: String): String = "base-colour-$key"

/** The name of a stored base colour, on the Appearance page and in the category's summary on the main page. */
@StringRes
internal fun baseColourName(key: String): Int = when (key) {
    LauncherSettings.BASE_COLOUR_BLUE -> R.string.base_colour_blue
    LauncherSettings.BASE_COLOUR_GREEN -> R.string.base_colour_green
    LauncherSettings.BASE_COLOUR_VIOLET -> R.string.base_colour_violet
    LauncherSettings.BASE_COLOUR_ROSE -> R.string.base_colour_rose
    LauncherSettings.BASE_COLOUR_TEAL -> R.string.base_colour_teal
    LauncherSettings.BASE_COLOUR_YELLOW -> R.string.base_colour_yellow
    LauncherSettings.BASE_COLOUR_RED -> R.string.base_colour_red
    LauncherSettings.BASE_COLOUR_BLACK -> R.string.base_colour_black
    LauncherSettings.BASE_COLOUR_CHAMELEON -> R.string.base_colour_chameleon
    else -> R.string.base_colour_orange
}

/** The Appearance page. */
@Composable
internal fun AppearanceRows(s: LauncherSettings, actions: SettingsActions) {
    SettingRow("AP1") { BaseColourCard(selected = s.baseColour, onPick = actions::setBaseColour) }
    SettingRow("AP2") {
        SwitchRow(
            stringResource(R.string.settings_charging_animation),
            stringResource(R.string.settings_charging_animation_desc),
            s.chargingAnimation,
            actions::setChargingAnimation,
        )
    }
}

/**
 * The base colour: nine named colours in a grid and the Chameleon, which follows the wallpaper, as a row of its own
 * with the line that says so. A tap applies at once; the whole launcher, this page included, takes the colour. On a
 * narrow screen or with large text the grid is two columns wide so that no name is cut.
 */
@Composable
internal fun BaseColourCard(selected: String, onPick: (String) -> Unit) {
    val p = LocalExpressivePalette.current
    val dark = currentBasePalette().dark
    Surface(
        shape = expressiveCardShape(),
        color = p.surfaceHi,
        shadowElevation = expressiveCardShadow(),
        modifier = Modifier.fillMaxWidth().testTag(BASE_COLOUR_CARD_TAG),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(
                stringResource(R.string.settings_base_colour),
                color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Text(
                stringResource(R.string.settings_base_colour_desc),
                color = p.dim, fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 6.dp).padding(bottom = 8.dp),
            )
            val fixed = LauncherSettings.BASE_COLOURS.filter { it != LauncherSettings.BASE_COLOUR_CHAMELEON }
            val names = fixed.map { stringResource(baseColourName(it)) }
            // A name is one word and cannot wrap, so the grid is as many columns wide as the widest name allows in
            // this language at this text size: three, two, or a plain list.
            val nameStyle = LocalTextStyle.current.merge(fontSize = OPTION_NAME_SIZE, fontWeight = FontWeight.SemiBold)
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            BoxWithConstraints {
                val widestName = remember(names, nameStyle, density) {
                    names.maxOf { measurer.measure(it, nameStyle, maxLines = 1).size.width }
                }
                val sidePadding = with(density) { (OPTION_SIDE_PADDING * 2).roundToPx() }
                val columns = listOf(3, 2).firstOrNull { constraints.maxWidth / it - sidePadding > widestName } ?: 1
                Column(modifier = Modifier.selectableGroup()) {
                    if (columns == 1) {
                        fixed.forEach { key ->
                            BaseColourRowOption(key, description = null, selected == key, dark) { onPick(key) }
                        }
                    } else {
                        fixed.chunked(columns).forEach { rowKeys ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                rowKeys.forEach { key ->
                                    BaseColourOption(key, selected == key, dark, Modifier.weight(1f)) { onPick(key) }
                                }
                                repeat(columns - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    BaseColourRowOption(
                        LauncherSettings.BASE_COLOUR_CHAMELEON,
                        description = stringResource(R.string.base_colour_chameleon_desc),
                        selected == LauncherSettings.BASE_COLOUR_CHAMELEON,
                        dark,
                    ) { onPick(LauncherSettings.BASE_COLOUR_CHAMELEON) }
                }
            }
        }
    }
}

private val OPTION_NAME_SIZE = 13.sp
private val OPTION_SIDE_PADDING = 4.dp

@Composable
private fun BaseColourOption(key: String, selected: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalExpressivePalette.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .testTag(baseColourTag(key))
            .pressSpring(interaction)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) p.container else Color.Transparent)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = OPTION_SIDE_PADDING, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BaseColourSwatch(key, selected, dark)
        Text(
            stringResource(baseColourName(key)),
            color = if (selected) p.onContainer else p.text,
            fontSize = OPTION_NAME_SIZE,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A choice as a full-width row, its name beside its swatch: the Chameleon with the line that explains it, and
 *  every colour when the names are too wide for a grid. */
@Composable
private fun BaseColourRowOption(key: String, description: String?, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    val p = LocalExpressivePalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag(baseColourTag(key))
            .pressSpring(interaction)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) p.container else Color.Transparent)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaseColourSwatch(key, selected, dark)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                stringResource(baseColourName(key)),
                color = if (selected) p.onContainer else p.text,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (description != null) {
                Text(description, color = if (selected) p.onContainer else p.dim, fontSize = 12.5.sp)
            }
        }
    }
}

/** A choice's own colour as a round swatch; the chosen one carries a check on a badge. The badge is the colour
 *  read on a container with the check in the container's own: the chosen swatch IS the current fill, and a badge
 *  of the fill sank into it. */
@Composable
private fun BaseColourSwatch(key: String, selected: Boolean, dark: Boolean) {
    val p = LocalExpressivePalette.current
    val colour = remember(key, dark) { swatchBrush(key, dark) }
    Box(modifier = Modifier.size(40.dp)) {
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(colour).border(1.dp, p.faint, CircleShape))
        if (selected) {
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(p.onContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(LauncherIcons.Check),
                    contentDescription = null,
                    tint = p.container,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * What a swatch is painted with: a hue's own fill in the current mode; Black as the near-black its light look fills
 * with (in dark its fill is white, which would not say "black"); the Chameleon as every hue at once, since its
 * colour is the wallpaper's.
 */
private fun swatchBrush(key: String, dark: Boolean): Brush = when (key) {
    LauncherSettings.BASE_COLOUR_BLACK -> SolidColor(basePalette(PaletteSeed.Mono, dark = false).fill)
    LauncherSettings.BASE_COLOUR_CHAMELEON -> Brush.sweepGradient(
        (CHAMELEON_HUES + CHAMELEON_HUES.first()).map { basePalette(PaletteSeed.Hue(it), dark).fill },
    )
    else -> SolidColor(basePalette(paletteSeed(key, null), dark).fill)
}

private val CHAMELEON_HUES = listOf(15f, 46f, 143f, 182f, 220f, 275f, 335f)
