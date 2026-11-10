package org.arkikeskus.launcher.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The look of an open folder (a Telegram request: app names on or off, icon size, density and a
 * background colour of its own). The card's text turns dark or light by what the card looks like:
 * its colour at its opacity over the dimmed home screen behind it.
 */
class FolderStyleTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val yellow = 0xFFFFEE58.toInt()
    private val blue = 0xFF42A5F5.toInt()

    @Test fun luminanceRunsFromBlackToWhite() {
        assertThat(argbLuminance(black)).isWithin(0.001f).of(0f)
        assertThat(argbLuminance(white)).isWithin(0.001f).of(1f)
        assertThat(argbLuminance(yellow)).isGreaterThan(0.7f)
        assertThat(argbLuminance(blue)).isLessThan(0.5f)
        // Alpha is not part of it.
        assertThat(argbLuminance(0x00FFFFFF)).isWithin(0.001f).of(1f)
    }

    @Test fun aSolidLightCardTakesDarkContentAndADarkOneLight() {
        assertThat(contentIsDarkOn(argbLuminance(white), opacity = 1f)).isTrue()
        assertThat(contentIsDarkOn(argbLuminance(yellow), opacity = 1f)).isTrue()
        assertThat(contentIsDarkOn(argbLuminance(black), opacity = 1f)).isFalse()
        assertThat(contentIsDarkOn(argbLuminance(blue), opacity = 1f)).isFalse()
    }

    /** A see-through white card over the dimmed home screen is a dark card: its text stays light. */
    @Test fun aSeeThroughLightCardTakesLightContent() {
        assertThat(contentIsDarkOn(argbLuminance(white), opacity = 0.3f)).isFalse()
        assertThat(contentIsDarkOn(argbLuminance(white), opacity = 0.8f)).isTrue()
    }

    @Test fun theSettingsCarryTheFolderStyle() {
        assertThat(LauncherSettings().folderStyle()).isEqualTo(FolderStyle())
        val settings = LauncherSettings(
            folderShowLabels = false, folderIconScale = 0.8f, folderSpacing = 0.5f,
            folderBackgroundColor = blue, folderBackgroundOpacity = 0.6f,
        )
        assertThat(settings.folderStyle()).isEqualTo(
            FolderStyle(showLabels = false, iconScale = 0.8f, spacing = 0.5f, backgroundColor = blue, backgroundOpacity = 0.6f),
        )
    }
}
