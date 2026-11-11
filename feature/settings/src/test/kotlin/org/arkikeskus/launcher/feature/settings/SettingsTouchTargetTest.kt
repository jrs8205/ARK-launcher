package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.IconPackInfo
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The small controls of Settings take a touch anywhere in a 48 dp square around them (drawn smaller, they would
 * otherwise be easy to miss), and the options of a dialog, which lie edge to edge, are each 48 dp high.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, so a row is as high as it is on a phone
class SettingsTouchTargetTest {
    @get:Rule val compose = createComposeRule()
    private val res = RuntimeEnvironment.getApplication().resources

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides settingsPalette(dark = false)) { Column { content() } }
        }
        compose.waitForIdle()
    }

    /** A tap [dx], [dy] dp from the middle of the node. */
    private fun SemanticsNodeInteraction.tapFromCentre(dx: Int, dy: Int) {
        performTouchInput { click(center + Offset(dx.dp.toPx(), dy.dp.toPx())) }
        compose.waitForIdle()
    }

    /** The four points just inside a 48 dp square around a control's middle. */
    private val edges = listOf(-23 to 0, 23 to 0, 0 to -23, 0 to 23)

    @Test fun aSteppersButtonsTakeATouchIn48Dp() {
        val changes = mutableListOf<Int>()
        show { StepperRow("Columns", value = 4, min = 3, max = 7, onValueChange = { changes += it }) }
        for ((dx, dy) in edges) {
            changes.clear()
            compose.onNodeWithContentDescription(res.getString(R.string.settings_step_decrease)).tapFromCentre(dx, dy)
            assertThat(changes).containsExactly(3)
            changes.clear()
            compose.onNodeWithContentDescription(res.getString(R.string.settings_step_increase)).tapFromCentre(dx, dy)
            assertThat(changes).containsExactly(5)
        }
    }

    @Test fun neighbouringColourSwatchesEachTakeTheirOwn48Dp() {
        val picked = mutableListOf<Int?>()
        val (first, second) = COLOR_SWATCHES[0] to COLOR_SWATCHES[1]
        show { CardColorRow(title = "Colour", selected = null, onPick = { picked += it }) }
        for ((dx, dy) in edges) {
            picked.clear()
            compose.onNodeWithTag(colorSwatchTag(second)).tapFromCentre(dx, dy)
            assertThat(picked).containsExactly(second)
        }
        // The side that faces the neighbour belongs to the swatch it is nearer to.
        picked.clear()
        compose.onNodeWithTag(colorSwatchTag(first)).tapFromCentre(23, 0)
        assertThat(picked).containsExactly(first)
    }

    @Test fun aChoiceDialogsOptionsAre48DpHigh() {
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
        compose.onNode(hasText(res.getString(R.string.settings_page_speed_normal))).assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText(res.getString(R.string.settings_page_speed_fast))).assertHeightIsAtLeast(48.dp)
    }

    @Test fun theAppPickersOptionsAre48DpHigh() {
        val mail = AppItem(
            packageName = "com.mail", className = "Mail.Main", user = Process.myUserHandle(), userSerial = 0, label = "Mail",
        )
        show { SingleAppPicker(apps = listOf(mail), onPick = {}, onDismiss = {}) }
        compose.onNode(hasText(res.getString(R.string.settings_left_edge_none))).assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText("Mail")).assertHeightIsAtLeast(48.dp)
    }

    @Test fun theIconPackDialogsOptionsAre48DpHigh() {
        show {
            IconPackPicker(packs = listOf(IconPackInfo("com.pack", "Round icons")), selected = "", onPick = {}, onDismiss = {})
        }
        compose.onNode(hasText(res.getString(R.string.settings_icon_pack_system))).assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText("Round icons")).assertHeightIsAtLeast(48.dp)
    }

    @Test fun theSoundSourceDialogsOptionsAre48DpHigh() {
        show {
            LockSoundsSection(
                enabled = true, lockUri = "", unlockUri = "", soundName = { it },
                onSetEnabled = {}, onChoose = { _, _ -> }, onPreview = {}, onOpenSoundSettings = {},
            )
        }
        compose.onNode(hasText(res.getString(R.string.settings_lock_sound))).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(res.getString(R.string.settings_sound_from_files))).assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText(res.getString(R.string.settings_sound_from_phone))).assertHeightIsAtLeast(48.dp)
    }
}
