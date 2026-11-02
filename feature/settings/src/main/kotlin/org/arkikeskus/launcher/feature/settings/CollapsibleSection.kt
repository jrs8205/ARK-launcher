package org.arkikeskus.launcher.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/**
 * A settings section that folds under its title (a Telegram request): the title row is a button
 * with a chevron that turns, and [content] shows only while the section is open. Which sections are
 * folded is the host's state ([collapsed], flipped through [onToggle]), kept in the settings so the
 * page opens the way it was left.
 */
@Composable
internal fun CollapsibleSection(
    title: String,
    collapsed: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalExpressivePalette.current
    val state = stringResource(if (collapsed) R.string.settings_section_collapsed else R.string.settings_section_expanded)
    // The old title's rhythm (26 dp above, 12 below) minus the row's own touch padding.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { onToggle(!collapsed) }
            .semantics { stateDescription = state }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp, modifier = Modifier.weight(1f),
        )
        val turn by animateFloatAsState(targetValue = if (collapsed) 0f else 180f, label = "chevron")
        Icon(
            painter = painterResource(LauncherIcons.ExpandMore),
            contentDescription = null,
            tint = p.dim,
            modifier = Modifier.rotate(turn),
        )
    }
    AnimatedVisibility(
        visible = !collapsed,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(content = content)
    }
}
