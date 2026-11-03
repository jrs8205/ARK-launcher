package org.arkikeskus.launcher.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * The launcher theme. Defaults to the branded Arkikeskus color scheme; Material You dynamic color
 * is opt-in via [dynamicColor] (off by default so the brand identity holds out of the box).
 */
@Composable
fun LauncherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    /** The user's font for every text the launcher draws itself ([withFontFamily]). */
    fontFamily: FontFamily = FontFamily.Default,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val launcherColors = if (darkTheme) DarkLauncherColors else LightLauncherColors

    CompositionLocalProvider(LocalLauncherColors provides launcherColors) {
        val typography = remember(fontFamily) { LauncherTypography.withFontFamily(fontFamily) }
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
