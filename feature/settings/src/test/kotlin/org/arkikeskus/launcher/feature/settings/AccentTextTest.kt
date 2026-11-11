package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Accent-coloured TEXT in Settings takes the palette's accent, which reads on the light theme, and never the bare
 * orange (2.9:1 on a light card). One test per kind of place the orange used to be written with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AccentTextTest {
    @get:Rule val compose = createComposeRule()
    private val res = RuntimeEnvironment.getApplication().resources
    private val light = settingsPalette(dark = false)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalExpressivePalette provides light) { Column { content() } } }
        compose.waitForIdle()
    }

    /** The colour the text [text] is drawn in. */
    private fun colourOf(text: String): Color {
        val node = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().layoutInput.style.color
    }

    private fun assertAccentText(text: String) {
        assertWithMessage("\"$text\" is written in the palette's accent").that(colourOf(text)).isEqualTo(light.accent)
    }

    @Test fun theLightAccentIsNotTheBareOrange() {
        assertThat(light.accent).isNotEqualTo(Accent)
    }

    @Test fun aChoiceDialogsChosenOptionAndItsCloseButton() {
        show {
            ChoicePicker(
                title = R.string.settings_page_switch_speed,
                options = listOf(
                    LauncherSettings.SPEED_NORMAL to R.string.settings_page_speed_normal,
                    LauncherSettings.SPEED_FAST to R.string.settings_page_speed_fast,
                ),
                selected = LauncherSettings.SPEED_FAST,
                onPick = {},
                onDismiss = {},
            )
        }
        assertAccentText(res.getString(R.string.settings_page_speed_fast))
        assertAccentText(res.getString(R.string.settings_close))
        assertThat(colourOf(res.getString(R.string.settings_page_speed_normal))).isEqualTo(light.text)
    }

    @Test fun theAppPickersNoneOptionAndItsCloseButton() {
        show { SingleAppPicker(apps = emptyList(), onPick = {}, onDismiss = {}) }
        assertAccentText(res.getString(R.string.settings_left_edge_none))
        assertAccentText(res.getString(R.string.settings_close))
    }

    @Test fun theIconPackDialogsChosenOptionAndItsCloseButton() {
        show { IconPackPicker(packs = emptyList(), selected = "", onPick = {}, onDismiss = {}) }
        assertAccentText(res.getString(R.string.settings_icon_pack_system))
        assertAccentText(res.getString(R.string.settings_close))
    }

    @Test fun theBatchTimesEditorsButtons() {
        show { BatchTimesEditor(initial = "08:00", onSave = {}, onDismiss = {}) }
        assertAccentText(res.getString(R.string.settings_people_batch_times_save))
        assertAccentText(res.getString(R.string.settings_close))
    }

    @Test fun theDrawerPicturesButtons() {
        show { DrawerWallpaperRow(wallpaper = null, onPick = {}, onClear = {}) }
        assertAccentText(res.getString(R.string.settings_drawer_wallpaper_pick))
    }

    @Test fun aSlidersDefaultChip() {
        val percent: (Float) -> String = { res.getString(R.string.settings_percent, scalePercent(it)) }
        show { ScaleSliderRow(label = "Size", value = 1.2f, onValueChange = {}, valueRange = 0.8f..1.6f, valueText = percent) }
        assertAccentText(res.getString(R.string.settings_reset_default, percent(1f)))
    }

    @Test fun aSoundRowsLabel() {
        show {
            LockSoundsSection(
                enabled = true, lockUri = "", unlockUri = "", soundName = { it },
                onSetEnabled = {}, onChoose = { _, _ -> }, onPreview = {}, onOpenSoundSettings = {},
            )
        }
        assertAccentText(res.getString(R.string.settings_lock_sound))
    }
}
