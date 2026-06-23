package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Material 3 color schemes mirroring the Arkikeskus brand identity so the launcher and the
 * Arkikeskus app share a visual language. Values come from the Arkikeskus theme + design handoff.
 */
internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1B53C0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001551),
    secondary = Color(0xFF1E7D43),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB8F0C4),
    onSecondaryContainer = Color(0xFF00210E),
    tertiary = Color(0xFFB5530F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF341100),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFCFF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFBFCFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFDEE2F0),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF74777F),
)

internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF00419E),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF8FD89E),
    onSecondary = Color(0xFF00391B),
    secondaryContainer = Color(0xFF00522B),
    onSecondaryContainer = Color(0xFFABF5B8),
    tertiary = Color(0xFFFFAB8A),
    onTertiary = Color(0xFF552100),
    tertiaryContainer = Color(0xFF783200),
    onTertiaryContainer = Color(0xFFFFDCC2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E6ED),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E6ED),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
)
