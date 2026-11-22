package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The set-as-default card on the home screen (issue #6: it could not be closed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DefaultLauncherReminderTest {
    @get:Rule val compose = createComposeRule()

    private var setDefaultTaps = 0
    private var notNowTaps = 0

    private fun setCard() = compose.setContent {
        DefaultLauncherReminder(onSetDefault = { setDefaultTaps++ }, onNotNow = { notNowTaps++ })
    }

    @Test
    fun notNowAsksToHideTheCardWithoutSettingTheDefault() {
        setCard()
        compose.onNodeWithText("Not now").performClick()
        assertThat(notNowTaps).isEqualTo(1)
        assertThat(setDefaultTaps).isEqualTo(0)
    }

    @Test
    fun theMainButtonStillOffersToSetTheDefault() {
        setCard()
        compose.onNodeWithText("Set as default launcher").performClick()
        assertThat(setDefaultTaps).isEqualTo(1)
        assertThat(notNowTaps).isEqualTo(0)
    }
}

class DefaultReminderVisibilityTest {
    @Test
    fun theCardWaitsForTheStoredSettingsSoADismissedCardDoesNotFlashAtStartup() {
        // Until the home state has loaded, "dismissed" still reads its default (false).
        assertThat(showDefaultReminder(loaded = false, isDefault = false, showOnboarding = false, dismissed = false, folderOpen = false)).isFalse()
    }

    @Test
    fun onceLoadedItShowsOnlyWhileNotDefaultNotOnboardingAndNotDismissed() {
        assertThat(showDefaultReminder(loaded = true, isDefault = false, showOnboarding = false, dismissed = false, folderOpen = false)).isTrue()
        assertThat(showDefaultReminder(loaded = true, isDefault = true, showOnboarding = false, dismissed = false, folderOpen = false)).isFalse()
        assertThat(showDefaultReminder(loaded = true, isDefault = false, showOnboarding = true, dismissed = false, folderOpen = false)).isFalse()
        assertThat(showDefaultReminder(loaded = true, isDefault = false, showOnboarding = false, dismissed = true, folderOpen = false)).isFalse()
    }

    /** The open folder is a card in the home window at the screen's centre, where the reminder sits
     *  too: composed later, the reminder drew over the folder's name and apps and took their taps. The
     *  reminder steps aside while a folder is open and returns when it closes. */
    @Test
    fun anOpenFolderHidesTheCardSoItNeverDrawsOverTheFolder() {
        assertThat(showDefaultReminder(loaded = true, isDefault = false, showOnboarding = false, dismissed = false, folderOpen = true)).isFalse()
        assertThat(showDefaultReminder(loaded = true, isDefault = false, showOnboarding = false, dismissed = false, folderOpen = false)).isTrue()
    }
}
