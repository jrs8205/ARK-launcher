package org.arkikeskus.launcher.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.withFontFamily
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Test

/**
 * The launcher's font (a Telegram request after 0.9.9): six families Android ships, one per settings
 * key, the default sans for anything unknown; and the theme's typography takes the family in every
 * style, so a text that names no family inherits it.
 */
class LauncherFontsTest {

    @Test fun everyKeyHasItsOwnFamilyAndUnknownIsTheDefault() {
        val families = LauncherSettings.FONTS.map { launcherFontFamily(it) }
        assertThat(families.toSet()).hasSize(LauncherSettings.FONTS.size)
        assertThat(launcherFontFamily(LauncherSettings.FONT_DEFAULT)).isEqualTo(FontFamily.Default)
        assertThat(launcherFontFamily(LauncherSettings.FONT_SERIF)).isEqualTo(FontFamily.Serif)
        assertThat(launcherFontFamily(LauncherSettings.FONT_MONOSPACE)).isEqualTo(FontFamily.Monospace)
        assertThat(launcherFontFamily(LauncherSettings.FONT_CURSIVE)).isEqualTo(FontFamily.Cursive)
        assertThat(launcherFontFamily("comic")).isEqualTo(FontFamily.Default)
    }

    @Test fun theTypographyTakesTheFamilyInEveryStyle() {
        val typography = Typography().withFontFamily(FontFamily.Serif)
        val styles = listOf(
            typography.displayLarge, typography.displayMedium, typography.displaySmall,
            typography.headlineLarge, typography.headlineMedium, typography.headlineSmall,
            typography.titleLarge, typography.titleMedium, typography.titleSmall,
            typography.bodyLarge, typography.bodyMedium, typography.bodySmall,
            typography.labelLarge, typography.labelMedium, typography.labelSmall,
        )
        assertThat(styles.map { it.fontFamily }).containsExactlyElementsIn(List(15) { FontFamily.Serif })
        // The sizes and weights are the theme's own, untouched.
        assertThat(typography.bodyLarge.fontSize).isEqualTo(Typography().bodyLarge.fontSize)
        assertThat(typography.titleLarge.fontWeight).isEqualTo(Typography().titleLarge.fontWeight)
    }
}
