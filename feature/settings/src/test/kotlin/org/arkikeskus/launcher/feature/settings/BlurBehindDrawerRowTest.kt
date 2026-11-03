package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The "blur behind the drawer" switch tells the truth about the device: usable where Android blurs
 * across windows, greyed with the reason on Android 11 and on a phone whose display stack cannot blur
 * (a Fairphone 6 report), where the 0.9.9 switch silently did nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class BlurBehindDrawerRowTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val picked = mutableListOf<Boolean>()

    private fun show(availability: BlurAvailability, checked: Boolean = false, drawerOpaque: Boolean = false) {
        compose.setContent {
            BlurBehindDrawerRow(
                availability = availability,
                checked = checked,
                drawerOpaque = drawerOpaque,
                onCheckedChange = { picked += it },
            )
        }
        compose.waitForIdle()
    }

    /** The owner turned the blur on with a solid drawer and saw a black drawer: the row now says what to do. */
    @Test fun withTheBlurOnBehindASolidDrawerTheRowSaysToLowerTheOpacity() {
        show(BlurAvailability.AVAILABLE, checked = true, drawerOpaque = true)

        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_opaque)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_desc)).assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsEnabled().assertIsOn()
    }

    /** The hint is for a blur that is on and invisible: with the blur off the usual text stays. */
    @Test fun aSolidDrawerWithTheBlurOffKeepsTheUsualText() {
        show(BlurAvailability.AVAILABLE, checked = false, drawerOpaque = true)

        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_desc)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_opaque)).assertDoesNotExist()
    }

    @Test fun aSeeThroughDrawerWithTheBlurOnKeepsTheUsualText() {
        show(BlurAvailability.AVAILABLE, checked = true, drawerOpaque = false)

        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_desc)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_opaque)).assertDoesNotExist()
    }

    /** The system pausing the blur (battery saver, a video, a developer option) is not the device's fault: the switch
     *  stays usable and says the blur is paused, before any opacity hint. */
    @Test fun aPausedBlurKeepsTheSwitchUsableAndSaysSo() {
        show(BlurAvailability.PAUSED, checked = true, drawerOpaque = true)

        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_paused)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_opaque)).assertDoesNotExist()
        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_unavailable)).assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsEnabled().assertIsOn().performClick()

        assertThat(picked).containsExactly(false)
    }

    /** The device's reason wins over the opacity hint. */
    @Test fun aDeviceThatCannotBlurSaysSoEvenBehindASolidDrawer() {
        show(BlurAvailability.DEVICE_CANNOT, checked = true, drawerOpaque = true)

        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_opaque)).assertDoesNotExist()
    }

    @Test fun whereTheSystemBlursTheSwitchWorksAsBefore() {
        show(BlurAvailability.AVAILABLE)

        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_desc)).assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsEnabled().assertIsOff().performClick()

        assertThat(picked).containsExactly(true)
    }

    @Test fun onAndroid11TheSwitchIsGreyedAndSaysWhy() {
        show(BlurAvailability.NEEDS_ANDROID_12)

        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_needs_12)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_desc)).assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsNotEnabled().performClick()

        assertThat(picked).isEmpty()
    }

    /** The Fairphone 6: Android 16, but no blur behind windows. A stored "on" still shows as on, greyed. */
    @Test fun onADeviceThatCannotBlurTheSwitchIsGreyedAndSaysWhy() {
        show(BlurAvailability.DEVICE_CANNOT, checked = true)

        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_blur_behind_drawer_desc)).assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsNotEnabled().assertIsOn().performClick()

        assertThat(picked).isEmpty()
    }
}
