package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.screensaverPreferences
import org.arkikeskus.launcher.feature.screensaver.dream.DreamAccent
import org.arkikeskus.launcher.feature.screensaver.dream.DreamStyle
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.arkikeskus.launcher.feature.screensaver.R as ScreensaverR

/**
 * The Screensaver page: every setting of the screensaver, read from and written straight to its own file,
 * with the six styles as thumbnails the screensaver's own code draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensaverSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.screensaverPreferences()
    private val res get() = context.resources

    @Before fun nothingStored() {
        SettingsScrollMemory.clear()
        prefs.edit().clear().commit()
    }

    private fun show() {
        compose.setContent {
            SettingsContent(
                s = LauncherSettings(),
                lists = SettingsLists(),
                actions = RecordedActions().actions,
                blurAvailability = BlurAvailability.AVAILABLE,
                openPage = SETTINGS_PAGE_SCREENSAVER,
            )
        }
        compose.waitForIdle()
    }

    private fun row(id: String) = compose.onNodeWithTag(settingTag(id))

    private fun inRow(id: String) = hasAnyAncestor(hasTestTag(settingTag(id)))

    private fun switchOf(id: String) = compose.onNode(isToggleable() and inRow(id), useUnmergedTree = true)

    private fun toggle(id: String) {
        row(id).performScrollTo()
        switchOf(id).performClick()
        compose.waitForIdle()
    }

    private fun pick(style: DreamStyle) {
        compose.onNodeWithTag(screensaverStyleTag(style)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun rowSays(id: String, text: String) =
        compose.onNode(hasTestTag(settingTag(id)) and hasAnyDescendant(hasText(text)), useUnmergedTree = true)

    private val brightness get() = compose.activity.window.attributes.screenBrightness

    @Test fun allSixStylesAreOfferedAndTappingOneStoresIt() {
        show()
        for (style in DreamStyle.entries) compose.onNodeWithTag(screensaverStyleTag(style)).assertExists()
        assertThat(DreamStyle.entries).hasSize(6)
        // Nothing stored yet: the Glow style is the chosen one.
        compose.onNodeWithTag(screensaverStyleTag(DreamStyle.GLOW)).assertIsSelected()

        pick(DreamStyle.RING)

        assertThat(prefs.getString(DreamPreferences.KEY_STYLE, null)).isEqualTo("ring")
        compose.onNodeWithTag(screensaverStyleTag(DreamStyle.RING)).assertIsSelected()
    }

    @Test fun theStoredStyleIsTheChosenOneWhenThePageOpens() {
        prefs.edit().putString(DreamPreferences.KEY_STYLE, "edge").commit()
        show()
        compose.onNodeWithTag(screensaverStyleTag(DreamStyle.EDGE)).assertIsSelected()
    }

    @Test fun aSwitchTheChosenStyleHasNoPlaceForIsDisabledAndKeepsItsValue() {
        // The Night style shows the clock, the next alarm and the battery: it has no seconds.
        prefs.edit().putString(DreamPreferences.KEY_STYLE, "night").commit()
        show()

        row("SS6").performScrollTo()
        switchOf("SS6").assertIsNotEnabled().assertIsOn()
        switchOf("SS8").assertIsEnabled()
        assertThat(prefs.contains(DreamPreferences.KEY_SHOW_SECONDS)).isFalse()
    }

    @Test fun aSwitchWritesItsValueAtOnce() {
        show()
        toggle("SS7")
        assertThat(prefs.getBoolean(DreamPreferences.KEY_SHOW_DATE, true)).isFalse()
        toggle("SS15")
        assertThat(prefs.getBoolean(DreamPreferences.KEY_LAYOUT_SWAPPED, false)).isTrue()
    }

    @Test fun theCalendarRowsShowOnlyForTheGaugesStyle() {
        show()
        row("SS13").assertDoesNotExist()
        row("SS14").assertDoesNotExist()

        pick(DreamStyle.GAUGES)

        row("SS13").assertExists()
        row("SS14").assertExists()
        // The titles follow the calendar rows: off until those are on.
        switchOf("SS14").assertIsNotEnabled()
    }

    @Test fun theAccentIsStoredAndHasNoUseWithTheSecondsOff() {
        show()
        val sky = compose.onNodeWithTag(colorSwatchTag(DreamAccent.SKY.color.toArgb()))
        val teal = compose.onNodeWithTag(colorSwatchTag(DreamAccent.TEAL.color.toArgb()))
        row("SS12").performScrollTo()
        sky.assertIsSelected()

        teal.performClick()
        compose.waitForIdle()
        assertThat(prefs.getString(DreamPreferences.KEY_ACCENT, null)).isEqualTo("teal")
        teal.assertIsSelected()

        // The accent is the colour of the seconds.
        toggle("SS6")
        teal.assertIsNotEnabled()
    }

    @Test fun theMissingNotificationAccessIsSaidOnTheNotificationRow() {
        show()
        row("SS9").performScrollTo()
        rowSays("SS9", res.getString(ScreensaverR.string.dream_notifications_access_missing)).assertExists()
    }

    @Test fun withNotificationAccessTheRowSaysWhatItShows() {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "${context.packageName}/org.arkikeskus.launcher.notifications.NotificationDotListenerService",
        )
        show()
        row("SS9").performScrollTo()
        rowSays("SS9", res.getString(ScreensaverR.string.dream_notifications_description)).assertExists()
    }

    @Test fun theNightStyleNeedsTheNightDimAndTheTimesComeWithIt() {
        show()
        row("SS21").performScrollTo()
        switchOf("SS21").assertIsNotEnabled()
        row("SS20").assertDoesNotExist()

        toggle("SS19")

        assertThat(prefs.getBoolean(DreamPreferences.KEY_NIGHT_DIM, false)).isTrue()
        switchOf("SS21").assertIsEnabled()
        // The defaults: from ten in the evening to seven in the morning.
        rowSays("SS20", res.getString(ScreensaverR.string.night_dim_start_label, "22:00")).assertExists()
        rowSays("SS20", res.getString(ScreensaverR.string.night_dim_end_label, "07:00")).assertExists()
    }

    @Test fun theNightDimsTimesShowTheStoredMinutes() {
        prefs.edit()
            .putBoolean(DreamPreferences.KEY_NIGHT_DIM, true)
            .putInt(DreamPreferences.KEY_NIGHT_DIM_START_MINUTES, 21 * 60 + 30)
            .putInt(DreamPreferences.KEY_NIGHT_DIM_END_MINUTES, 6 * 60 + 15)
            .commit()
        show()
        row("SS20").performScrollTo()
        rowSays("SS20", res.getString(ScreensaverR.string.night_dim_start_label, "21:30")).assertExists()
        rowSays("SS20", res.getString(ScreensaverR.string.night_dim_end_label, "06:15")).assertExists()
    }

    private fun slider() = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and inRow("SS23"),
        useUnmergedTree = true,
    )

    @Test fun theBrightnessSliderComesWithItsSwitch() {
        show()
        row("SS23").assertDoesNotExist()
        toggle("SS22")
        assertThat(prefs.getBoolean(DreamPreferences.KEY_OWN_BRIGHTNESS, false)).isTrue()
        row("SS23").assertExists()
        rowSays("SS23", "30%").assertExists()
    }

    @Test fun theBrightnessSliderLightsThePageWhileItMovesAndLetsGoAfter() {
        prefs.edit().putBoolean(DreamPreferences.KEY_OWN_BRIGHTNESS, true).commit()
        show()
        row("SS23").performScrollTo()
        assertThat(brightness).isEqualTo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        slider().performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
        }
        compose.waitForIdle()
        // There is no other way to judge a brightness than to see it: the page itself takes the level.
        assertThat(brightness).isGreaterThan(0f)
        assertThat(prefs.contains(DreamPreferences.KEY_BRIGHTNESS_PERCENT)).isFalse()

        slider().performTouchInput { up() }
        compose.waitForIdle()
        assertThat(brightness).isEqualTo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        assertThat(prefs.getInt(DreamPreferences.KEY_BRIGHTNESS_PERCENT, -1)).isIn(1..100)
    }

    @Test fun leavingThePageMidMoveGivesThePhonesBrightnessBack() {
        prefs.edit().putBoolean(DreamPreferences.KEY_OWN_BRIGHTNESS, true).commit()
        show()
        row("SS23").performScrollTo()
        slider().performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
        }
        compose.waitForIdle()
        assertThat(brightness).isGreaterThan(0f)

        // No word of the move having ended: the page is simply gone.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertThat(brightness).isEqualTo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        assertThat(prefs.contains(DreamPreferences.KEY_BRIGHTNESS_PERCENT)).isFalse()
    }

    @Test fun theStartRowOpensAndroidsScreensaverSettings() {
        show()
        row("SS2").performScrollTo()
        compose.onNode(hasText(res.getString(ScreensaverR.string.open_screensaver_settings))).performClick()
        compose.waitForIdle()

        val started = shadowOf(compose.activity).nextStartedActivity
        assertThat(started.action).isEqualTo(Settings.ACTION_DREAM_SETTINGS)
    }
}
