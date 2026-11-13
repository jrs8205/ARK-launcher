package org.arkikeskus.launcher.ui.expressive

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The springy press: something pressed shrinks a little and springs back, unless the phone's animations are off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PressSpringTest {
    @get:Rule val compose = createComposeRule()

    private val interaction = MutableInteractionSource()
    private lateinit var scale: State<Float>
    private lateinit var scope: CoroutineScope

    private fun animatorScale(value: Float) {
        Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, value,
        )
    }

    private fun show() {
        compose.setContent {
            scope = rememberCoroutineScope()
            scale = rememberPressScale(interaction)
        }
        compose.waitForIdle()
    }

    private fun press(): PressInteraction.Press {
        val press = PressInteraction.Press(Offset.Zero)
        compose.runOnIdle { scope.launch { interaction.emit(press) } }
        return press
    }

    @Test fun aPressShrinksAndAReleaseSpringsBack() {
        animatorScale(1f)
        show()
        assertThat(scale.value).isEqualTo(1f)

        compose.mainClock.autoAdvance = false
        val press = press()
        compose.mainClock.advanceTimeBy(64)
        // On its way: no longer at rest, and not a jump straight to the end.
        assertThat(scale.value).isLessThan(1f)
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(scale.value).isWithin(0.002f).of(PRESSED_SCALE)

        compose.runOnIdle { scope.launch { interaction.emit(PressInteraction.Release(press)) } }
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(scale.value).isWithin(0.002f).of(1f)
    }

    @Test fun withThePhonesAnimationsOffNothingMoves() {
        animatorScale(0f)
        show()
        compose.mainClock.autoAdvance = false
        press()
        compose.mainClock.advanceTimeBy(64)
        assertThat(scale.value).isEqualTo(1f)
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(scale.value).isEqualTo(1f)
    }
}
