package org.arkikeskus.launcher.ui.expressive

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.ui.LauncherIcons
import androidx.compose.ui.graphics.Color

@Composable
fun ExpressiveSectionTitle(text: String, color: Color = LocalExpressivePalette.current.text) {
    Text(
        text = text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        modifier = Modifier.padding(top = 26.dp, start = 4.dp, bottom = 12.dp),
    )
}

@Composable
fun ExpressiveCard(
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalExpressivePalette.current
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

/** Accent title + dim description, with a trailing vector icon (chevron = opens a sub-screen, add =
 *  "+"); the add icon is accented, the chevron is faint. */
@Composable
fun ExpressiveActionRow(
    label: String,
    description: String,
    @DrawableRes trailingIcon: Int = LauncherIcons.ChevronRight,
    onClick: () -> Unit,
) {
    val p = LocalExpressivePalette.current
    ExpressiveCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(description, color = p.dim, fontSize = 12.5.sp)
        }
        Icon(
            painter = painterResource(trailingIcon),
            contentDescription = null,
            tint = if (trailingIcon == LauncherIcons.Add) p.accent else p.faint,
            modifier = Modifier.size(22.dp),
        )
    }
}
