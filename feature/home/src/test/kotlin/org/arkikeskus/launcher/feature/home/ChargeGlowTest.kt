package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The glow that rises from the bottom of the home screen when the charger is connected. It plays for a
 * charger connected while the home screen is in front, once, for about a second; not for a phone that was
 * already charging when the home screen came up, not with the switch off, not with the system's animations off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChargeGlowTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var glow: ChargeGlowState

    @Before fun systemAnimationsOn() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun show(charging: MutableStateFlow<Boolean>, enabled: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent { glow = rememberChargeGlow(charging, enabled) }
        compose.mainClock.advanceTimeBy(100)
    }

    /** The strongest the glow gets over the next [millis], looked at every frame. */
    private fun peakOver(millis: Int): Float {
        var peak = glow.strength
        repeat(millis / 16) {
            compose.mainClock.advanceTimeByFrame()
            peak = maxOf(peak, glow.strength)
        }
        return peak
    }

    @Test fun onlyAChargerConnectedWhileWatchingStartsIt() {
        assertThat(chargeGlowStarts(before = false, now = true)).isTrue()
        // The first word on the battery says nothing about a change.
        assertThat(chargeGlowStarts(before = null, now = true)).isFalse()
        assertThat(chargeGlowStarts(before = true, now = true)).isFalse()
        assertThat(chargeGlowStarts(before = true, now = false)).isFalse()
        assertThat(chargeGlowStarts(before = false, now = false)).isFalse()
    }

    @Test fun connectingTheChargerOnTheHomeScreenGlowsOnceAndFades() {
        val charging = MutableStateFlow(false)
        show(charging)
        assertThat(glow.strength).isEqualTo(0f)

        charging.value = true

        assertThat(peakOver(600)).isGreaterThan(0.9f)
        // About a second in all: well before two seconds it is gone, and it does not come back.
        peakOver(1400)
        assertThat(glow.strength).isEqualTo(0f)
        assertThat(peakOver(1500)).isEqualTo(0f)
    }

    @Test fun aPhoneAlreadyChargingWhenHomeComesUpDoesNotGlow() {
        show(MutableStateFlow(true))

        assertThat(peakOver(1500)).isEqualTo(0f)
    }

    @Test fun takingTheChargerAwayDoesNotGlow() {
        val charging = MutableStateFlow(true)
        show(charging)

        charging.value = false

        assertThat(peakOver(1500)).isEqualTo(0f)
    }

    @Test fun withTheSwitchOffNothingGlows() {
        val charging = MutableStateFlow(false)
        show(charging, enabled = false)

        charging.value = true

        assertThat(peakOver(1500)).isEqualTo(0f)
    }

    @Test fun withTheSystemsAnimationsOffNothingGlows() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val charging = MutableStateFlow(false)
        show(charging)

        charging.value = true

        assertThat(peakOver(1500)).isEqualTo(0f)
    }

    // --- The battery glyph's sweep and the system's animations switch ------------------------------------

    private var sweep: (() -> Float)? = null

    private fun setSystemAnimations(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        // What the system does when the setting is written: its observers are told.
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun theSweepRunsWhileChargingWithAnimationsOn() {
        compose.mainClock.autoAdvance = false
        compose.setContent { sweep = rememberChargeSweep(active = true) }
        compose.mainClock.advanceTimeBy(900)

        assertThat(sweep).isNotNull()
        assertThat(sweep!!.invoke()).isGreaterThan(0f)
    }

    @Test fun thereIsNoSweepWhenNotCharging() {
        compose.setContent { sweep = rememberChargeSweep(active = false) }
        compose.waitForIdle()

        assertThat(sweep).isNull()
    }

    @Test fun switchingTheSystemsAnimationsOffStopsTheSweepThatIsRunning() {
        compose.mainClock.autoAdvance = false
        compose.setContent { sweep = rememberChargeSweep(active = true) }
        compose.mainClock.advanceTimeBy(900)
        val running = sweep!!

        setSystemAnimations(0f)
        compose.mainClock.advanceTimeBy(200)

        // The glyph is handed no sweep any more, and the one it was handed draws nothing in the meantime:
        // an endless animation at scale 0 jumps to its end, a glyph drawn full on a half-empty battery.
        assertThat(sweep).isNull()
        assertThat(running.invoke()).isEqualTo(0f)
    }

    @Test fun switchingTheSystemsAnimationsBackOnStartsTheSweepAgain() {
        setSystemAnimations(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent { sweep = rememberChargeSweep(active = true) }
        compose.mainClock.advanceTimeBy(100)
        assertThat(sweep).isNull()

        setSystemAnimations(1f)
        compose.mainClock.advanceTimeBy(900)

        assertThat(sweep).isNotNull()
    }

    @Test fun aSecondChargerConnectionGlowsAgain() {
        val charging = MutableStateFlow(false)
        show(charging)
        charging.value = true
        peakOver(2000)

        charging.value = false
        peakOver(200)
        charging.value = true

        assertThat(peakOver(600)).isGreaterThan(0.9f)
    }
}
