package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The blur switch's longest descriptions (the "lower the opacity" hint and the device reasons, a sentence each) must
 * stay a short paragraph in every language on a phone-width screen (411 dp, a Pixel): at most four lines of the
 * row's 12.5 sp text, so the row does not swell into a wall of text in French or Russian.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class BlurHintLengthTest {
    @get:Rule val compose = createComposeRule()

    private val languages = listOf("en", "fi", "de", "es", "fr", "pl", "pt", "ru", "sv", "tr")
    private val hints = listOf(
        R.string.settings_blur_behind_drawer_opaque,
        R.string.settings_blur_behind_drawer_unavailable,
        R.string.settings_blur_behind_drawer_needs_12,
        R.string.settings_blur_behind_drawer_desc,
    )

    @Test fun everyBlurDescriptionFitsFourLinesInEveryLanguage() {
        var language by mutableStateOf(languages.first())
        var hint by mutableStateOf(hints.first())
        var localized: Context? = null
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(language)) }
            val context = base.createConfigurationContext(config).also { localized = it }
            key(language, hint) {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                    BlurBehindDrawerRow(
                        availability = when (hint) {
                            R.string.settings_blur_behind_drawer_unavailable -> BlurAvailability.DEVICE_CANNOT
                            R.string.settings_blur_behind_drawer_needs_12 -> BlurAvailability.NEEDS_ANDROID_12
                            else -> BlurAvailability.AVAILABLE
                        },
                        checked = true,
                        drawerOpaque = hint == R.string.settings_blur_behind_drawer_opaque,
                        onCheckedChange = {},
                    )
                }
            }
        }
        for (lang in languages) {
            for (res in hints) {
                language = lang
                hint = res
                compose.waitForIdle()
                val text = localized!!.getString(res)
                val node = compose.onNodeWithText(text).fetchSemanticsNode()
                val results = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                val lines = results.single().lineCount
                val heightSp = with(compose.density) { node.size.height.toSp() }
                assertWithMessage("$lang ${localized!!.resources.getResourceEntryName(res)}: $lines lines, ${heightSp.value} sp\n$text")
                    .that(lines).isAtMost(4)
                assertWithMessage("$lang: the row's description keeps its 12.5 sp size").that(heightSp.value)
                    .isAtMost((12.5.sp * 1.5f * 4).value)
            }
        }
    }
}
