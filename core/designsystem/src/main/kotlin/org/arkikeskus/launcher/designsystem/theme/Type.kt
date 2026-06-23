package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LauncherTypography = Typography()

/**
 * Clock + app-label text styles per the three home styles in the design handoff.
 * Kept separate from the Material [Typography] slots since they are launcher-specific.
 */
object LauncherTextStyles {
    val clockSelkea = TextStyle(fontSize = 54.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
    val clockTietokeskus = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp)
    val clockIlmaisuvoimainen = TextStyle(fontSize = 78.sp, fontWeight = FontWeight.Bold, letterSpacing = (-3).sp)
    val appLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)
}
