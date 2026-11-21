package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.designsystem.theme.BasePalette
import org.arkikeskus.launcher.designsystem.theme.DefaultPaletteSeed
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.expressive.ExpressiveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Appearance page's one row: the ten base colours. Every choice is there with its name, the
 * stored one is the selected one, a tap reports the choice, and nothing is cut on a narrow screen
 * with large text in the longest languages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
// Real text measurement: the legacy one does not size text by the font, so "is a name cut" would mean nothing.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceSectionTest {
    @get:Rule val compose = createComposeRule()
    private val res get() = RuntimeEnvironment.getApplication().resources

    private fun show(
        selected: String,
        palette: BasePalette = basePalette(DefaultPaletteSeed, dark = false),
        fontScale: Float = 1f,
        onPick: (String) -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LauncherTheme(palette = palette) {
                    ExpressiveTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) { BaseColourCard(selected, onPick) }
                    }
                }
            }
        }
    }

    @Test fun everyBaseColourIsOfferedAndTheStoredOneIsSelected() {
        show(selected = LauncherSettings.BASE_COLOUR_TEAL)
        for (key in LauncherSettings.BASE_COLOURS) {
            val node = compose.onNodeWithTag(baseColourTag(key))
            node.assertExists()
            if (key == LauncherSettings.BASE_COLOUR_TEAL) node.assertIsSelected() else node.assertIsNotSelected()
        }
        compose.onAllNodesWithText(res.getString(R.string.base_colour_chameleon_desc)).onFirst().assertExists()
        // Classic says what it is: its swatch alone would be taken for the Orange choice.
        compose.onAllNodesWithText(res.getString(R.string.base_colour_classic_desc)).onFirst().assertExists()
    }

    @Test fun everyChoiceCarriesItsNameForTheScreenReader() {
        show(selected = LauncherSettings.BASE_COLOUR_ORANGE)
        val names = mapOf(
            LauncherSettings.BASE_COLOUR_ORANGE to "Orange", LauncherSettings.BASE_COLOUR_BLUE to "Blue",
            LauncherSettings.BASE_COLOUR_GREEN to "Green", LauncherSettings.BASE_COLOUR_VIOLET to "Violet",
            LauncherSettings.BASE_COLOUR_ROSE to "Rose", LauncherSettings.BASE_COLOUR_TEAL to "Teal",
            LauncherSettings.BASE_COLOUR_YELLOW to "Yellow", LauncherSettings.BASE_COLOUR_RED to "Red",
            LauncherSettings.BASE_COLOUR_BLACK to "Black", LauncherSettings.BASE_COLOUR_CHAMELEON to "Chameleon",
            LauncherSettings.BASE_COLOUR_CLASSIC to "Classic",
        )
        assertThat(names.keys).containsExactlyElementsIn(LauncherSettings.BASE_COLOURS)
        for ((key, name) in names) {
            val texts = compose.onNodeWithTag(baseColourTag(key)).fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertWithMessage(key).that(texts).contains(name)
        }
    }

    @Test fun aTapReportsTheChoice() {
        val picked = mutableListOf<String>()
        show(selected = LauncherSettings.BASE_COLOUR_ORANGE, onPick = { picked += it })
        compose.onNodeWithTag(baseColourTag(LauncherSettings.BASE_COLOUR_VIOLET)).performScrollTo().performClick()
        compose.onNodeWithTag(baseColourTag(LauncherSettings.BASE_COLOUR_CHAMELEON)).performScrollTo().performClick()
        compose.onNodeWithTag(baseColourTag(LauncherSettings.BASE_COLOUR_CLASSIC)).performScrollTo().performClick()
        assertThat(picked).containsExactly(
            LauncherSettings.BASE_COLOUR_VIOLET, LauncherSettings.BASE_COLOUR_CHAMELEON, LauncherSettings.BASE_COLOUR_CLASSIC,
        ).inOrder()
    }

    @Test fun everyChoiceIsATouchTargetOfAtLeast48Dp() {
        show(selected = LauncherSettings.BASE_COLOUR_ORANGE)
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        for (key in LauncherSettings.BASE_COLOURS) {
            val size = compose.onNodeWithTag(baseColourTag(key)).fetchSemanticsNode().size
            assertWithMessage("$key height").that(size.height / density).isAtLeast(48f)
            assertWithMessage("$key width").that(size.width / density).isAtLeast(48f)
        }
    }

    @Test fun theSelectionFollowsTheStoredChoiceWhateverThePaletteAround() {
        var selected by mutableStateOf(LauncherSettings.BASE_COLOUR_BLUE)
        var palette by mutableStateOf(basePalette(PaletteSeed.Hue(220f), dark = false))
        compose.setContent {
            LauncherTheme(palette = palette) { ExpressiveTheme { BaseColourCard(selected) {} } }
        }
        compose.onNodeWithTag(baseColourTag(LauncherSettings.BASE_COLOUR_BLUE)).assertIsSelected()
        // The wallpaper changes, or the phone goes dark, while the page is open: the choice stays chosen.
        palette = basePalette(PaletteSeed.Mono, dark = true)
        compose.waitForIdle()
        compose.onNodeWithTag(baseColourTag(LauncherSettings.BASE_COLOUR_BLUE)).assertIsSelected()
        selected = LauncherSettings.BASE_COLOUR_BLACK
        compose.waitForIdle()
        compose.onNodeWithTag(baseColourTag(LauncherSettings.BASE_COLOUR_BLACK)).assertIsSelected()
        compose.onNodeWithTag(baseColourTag(LauncherSettings.BASE_COLOUR_BLUE)).assertIsNotSelected()
    }

    private fun assertNoNameIsCut() {
        show(selected = LauncherSettings.BASE_COLOUR_YELLOW, fontScale = 2f)
        val rootWidth = compose.onNodeWithTag(BASE_COLOUR_CARD_TAG).fetchSemanticsNode().boundsInRoot.right
        for (key in LauncherSettings.BASE_COLOURS) {
            val node = compose.onNodeWithTag(baseColourTag(key), useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
            assertWithMessage("$key stays inside the card").that(node.boundsInRoot.right).isAtMost(rootWidth)
            val results = mutableListOf<TextLayoutResult>()
            fun collect(n: androidx.compose.ui.semantics.SemanticsNode) {
                n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                n.children.forEach(::collect)
            }
            collect(node)
            assertWithMessage("$key has a name").that(results).isNotEmpty()
            // A name is cut when it runs out of height, when a line is wider than its room, or when a one-word name
            // had to be broken over two lines. (A paragraph laid out wider than its text is not an overflow.)
            results.forEach {
                val text = it.layoutInput.text.text
                val at = "$key \"$text\" room=${it.layoutInput.constraints.maxWidth} lines=${it.lineCount}"
                assertWithMessage("$at: height").that(it.didOverflowHeight).isFalse()
                for (line in 0 until it.lineCount) {
                    assertWithMessage("$at: line $line").that(it.getLineRight(line) - it.getLineLeft(line))
                        .isAtMost(it.layoutInput.constraints.maxWidth.toFloat())
                }
                if (text.none(Char::isWhitespace)) assertWithMessage("$at: one word").that(it.lineCount).isEqualTo(1)
            }
        }
    }

    @Config(sdk = [35], application = Application::class, qualifiers = "de-w320dp-h640dp")
    @Test fun onANarrowScreenWithLargeTextNoGermanNameIsCut() = assertNoNameIsCut()

    @Config(sdk = [35], application = Application::class, qualifiers = "ru-w320dp-h640dp")
    @Test fun onANarrowScreenWithLargeTextNoRussianNameIsCut() = assertNoNameIsCut()
}
