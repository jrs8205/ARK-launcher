package org.arkikeskus.launcher.feature.settings

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A permission row asks for the permission; only when Android no longer shows the dialog (the user
 * denied it for good) is the app's system page the way left. A dialog the user merely backed out
 * of looks the same to the app (no grant, no rationale), so the first such answer is taken as a
 * dismissal and only a repeat sends the user to the system page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PermissionRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The system's answer to every request: no grant, as after a dismissed dialog. */
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, false as O)
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = registry
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PermissionRow(label = "Calendar", permission = Manifest.permission.READ_CALENDAR)
            }
        }
        compose.waitForIdle()
    }

    private fun appDetailsOpened(): Boolean =
        shadowOf(compose.activity).nextStartedActivity?.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS

    @Test fun aDismissedFirstRequestDoesNotOpenTheSystemPage() {
        show()

        compose.onNodeWithText("Calendar").performClick()
        compose.waitForIdle()

        assertThat(appDetailsOpened()).isFalse()
    }

    @Test fun aRepeatedDialoglessDenialOpensTheSystemPage() {
        show()

        compose.onNodeWithText("Calendar").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Calendar").performClick()
        compose.waitForIdle()

        assertThat(appDetailsOpened()).isTrue()
    }
}
