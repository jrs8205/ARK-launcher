package org.arkikeskus.launcher.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The look of an open folder (a Telegram request: app names on or off, icon size, density and a
 * background colour of its own). The text on a card of the user's colour is black or white by which
 * of the two has the better contrast with what the card really looks like: its colour at its opacity
 * over what is behind it.
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

    @Test fun contrastIsTheWcagRatio() {
        assertThat(contrastRatio(1f, 0f)).isWithin(0.01f).of(21f)
        assertThat(contrastRatio(0f, 1f)).isWithin(0.01f).of(21f)
        assertThat(contrastRatio(0.5f, 0.5f)).isWithin(0.001f).of(1f)
    }

    @Test fun aColourIsPaintedOverTheBackdropAtItsOpacity() {
        assertThat(compositeOver(white, 1f, black)).isEqualTo(white)
        assertThat(compositeOver(white, 0f, black)).isEqualTo(black)
        // 30 % white over black: 0.3 x 255 = 76.5 per channel.
        assertThat(compositeOver(white, 0.3f, black) and 0xFFFFFF).isEqualTo(0x4D4D4D)
    }

    @Test fun aSolidCardTakesTheTextWithTheBetterContrast() {
        assertThat(contentIsDarkOn(white, opacity = 1f, backdrop = black)).isTrue()
        assertThat(contentIsDarkOn(yellow, opacity = 1f, backdrop = black)).isTrue()
        assertThat(contentIsDarkOn(black, opacity = 1f, backdrop = white)).isFalse()
        // The offered blue: white text has 2.65:1 on it, black 7.9:1.
        assertThat(contentIsDarkOn(blue, opacity = 1f, backdrop = black)).isTrue()
        // A deep blue is the other way round.
        assertThat(contentIsDarkOn(0xFF0D47A1.toInt(), opacity = 1f, backdrop = white)).isFalse()
    }

    /** A see-through card is what shows through it: light text over a dark wallpaper, dark over a light one. */
    @Test fun aSeeThroughCardGoesByWhatIsBehindIt() {
        assertThat(contentIsDarkOn(white, opacity = 0.3f, backdrop = black)).isFalse()
        assertThat(contentIsDarkOn(white, opacity = 0.3f, backdrop = white)).isTrue()
        assertThat(contentIsDarkOn(black, opacity = 0.2f, backdrop = white)).isTrue()
        assertThat(contentIsDarkOn(black, opacity = 0.9f, backdrop = white)).isFalse()
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
