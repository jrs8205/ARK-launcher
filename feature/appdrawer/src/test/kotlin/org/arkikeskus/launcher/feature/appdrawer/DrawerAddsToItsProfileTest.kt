package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import android.os.Process
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the drawer adds to the home screen is added to the home profile the drawer's state was of when the user
 * asked, by that profile's grid, or not at all. "Add to home" read the grid from the settings and stored the row
 * in a transaction that read the profile again: with a switch between the two, the row went to the other profile
 * at a cell counted for the first one's columns (a fifth icon beside four full cells, never shown).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerAddsToItsProfileTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val mail = AppItem("com.mail", "com.mail.Main", Process.myUserHandle(), 0L, "Mail")

    private suspend fun DrawerWiring.onHome() =
        homeLayoutRepository.homeItems.first().firstOrNull { it.containerId == HOME && it.packageName == mail.packageName }

    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    @Test fun anAppAddedForAProfileThatIsNotOnIsNotAdded() = runTest {
        val wiring = DrawerWiring(context, this)
        try {
            val viewModel = wiring.viewModel()

            // The first profile is on; the drawer's state was another profile's when the user asked.
            val asked = viewModel.addToHome(mail, profile = 2)
            awaitUntil("the answer") { asked.isCompleted }

            assertThat(wiring.onHome()).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun anAppAddedForTheProfileThatIsOnIsAdded() = runTest {
        val wiring = DrawerWiring(context, this)
        try {
            val viewModel = wiring.viewModel()

            viewModel.addToHome(mail, profile = 1)

            awaitUntil("the app on home") { wiring.onHome() != null }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }
}
