package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Issue #27: on Android 13 and later the system greys out notification access for an app installed from a
 * file ("restricted setting") until the user allows restricted settings in the app's info. The launcher could
 * not be given the access and said nothing about why. While the access is missing, the Permissions page now
 * says what to do and leads to the app's info.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class RestrictedSettingsHintTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = RuntimeEnvironment.getApplication()

    private fun notificationAccess(granted: Boolean) {
        val listeners = if (granted) ComponentName(app.packageName, "Listener").flattenToString() else ""
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", listeners)
    }

    private fun showPage() {
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { PermissionsRows() } }
        compose.waitForIdle()
    }

    @Test fun theHintIsForAMissingAccessOnAndroid13AndLater() {
        assertThat(restrictedSettingsHintShown(accessGranted = false, sdkInt = 33)).isTrue()
        assertThat(restrictedSettingsHintShown(accessGranted = false, sdkInt = 37)).isTrue()
        // Before Android 13 nothing is restricted, and with the access there is nothing to explain.
        assertThat(restrictedSettingsHintShown(accessGranted = false, sdkInt = 32)).isFalse()
        assertThat(restrictedSettingsHintShown(accessGranted = true, sdkInt = 35)).isFalse()
    }

    @Test fun theRegistryKnowsTheRowAsOneThatDependsOnTheAccessRow() {
        val row = SettingsIndex.entry("P1a")

        assertThat(row.category).isEqualTo(SettingsCategory.PERMISSIONS)
        assertThat(row.fallback).isEqualTo("P1")
        assertThat(row.available(sdkInt = 32, privateSpaceSeen = false)).isFalse()
        assertThat(row.available(sdkInt = 33, privateSpaceSeen = false)).isTrue()
    }

    @Test fun withoutTheAccessThePageSaysWhatToDo() {
        notificationAccess(granted = false)
        showPage()

        compose.onNodeWithTag(settingTag("P1a")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.settings_restricted_hint)).assertIsDisplayed()
        val steps = app.getString(R.string.settings_restricted_hint_desc)
        compose.onNodeWithText(steps).assertIsDisplayed()
        assertThat(steps).contains("Allow restricted settings")
    }

    @Test fun aTapOpensTheAppsInfo() {
        notificationAccess(granted = false)
        showPage()

        compose.onNodeWithText(app.getString(R.string.settings_restricted_hint)).performScrollTo().performClick()

        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(started.data.toString()).isEqualTo("package:${app.packageName}")
    }

    @Test fun withTheAccessThereIsNoHint() {
        notificationAccess(granted = true)
        showPage()

        compose.onAllNodesWithTag(settingTag("P1a")).fetchSemanticsNodes().let { assertThat(it).isEmpty() }
    }
}
