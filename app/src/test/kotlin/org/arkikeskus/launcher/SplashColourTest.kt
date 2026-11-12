package org.arkikeskus.launcher

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The splash screen is drawn before any code runs, so it cannot be in the user's base colour. It
 * is neutral instead, light or dark with the phone, so that no fixed brand colour shows for a
 * moment before a launcher of another colour.
 */
class SplashColourTest {
    private fun splashColour(values: String): String? {
        val file = File("src/main/res/$values/colors.xml")
        if (!file.isFile) return null
        return Regex("""<color name="splash_background">(#[0-9A-Fa-f]{6,8})</color>""").find(file.readText())?.groupValues?.get(1)
    }

    @Test fun theSplashIsNeutralInBothModes() {
        assertWithMessage("light").that(splashColour("values")).isEqualTo("#FAFAFA")
        assertWithMessage("dark").that(splashColour("values-night")).isEqualTo("#000000")
    }
}
