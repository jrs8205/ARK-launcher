package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.ShortcutIconKey
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.model.CustomIcon
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Issue #23: the icon picked for a pinned shortcut goes with the shortcut's last copy in the same piece of
 * work as the row. Cleared after it, a restore queued behind the removal could re-file the shortcut with its
 * icon first and then lose the icon to the late clearing.
 *
 * The removal takes its profile's hold and then the pin lock, as a restore does, and the icon is cleared under
 * both. The test's "next one" is such a restore: it asks for the hold while the removal has it. The hold is
 * fair, so it runs right after the removal's whole work, and no moment has to be guessed. (What this cannot
 * tell apart is a clearing made under the hold but after the pin lock: nobody who takes both could come
 * between the two.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeViewModelShortcutIconLockTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val pack = CustomIcon.FromPack("com.example.pack", "octocat")

    @Test fun theIconIsClearedBeforeTheNextOneWhoTakesTheHoldAndThePinLockRuns() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            wiring.publishShortcut("github")
            val row = wiring.dao.insert(HomeItemEntity(packageName = context.packageName, shortcutId = "github", page = 0, cellX = 0, cellY = 0))
            val key = ShortcutIconKey.of(context.packageName, "github", 0L)
            wiring.settingsRepository.setShortcutIcon(key, pack)
            val viewModel = wiring.homeViewModel()

            // Someone holds the pin lock (a pin being confirmed): the removal takes its profile's hold and
            // then waits at the pin lock.
            val holding = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.Default) { ShortcutPinLock.withLock { holding.complete(Unit); release.await() } }
            holding.await()
            // Runs on this thread up to its first wait, and the hold is free: the removal HAS its profile's
            // hold when this returns (it then reads which profile is on, and goes on to the pin lock).
            viewModel.removeShortcut(row, profile = 1)
            var rowsWhenTheNextOneRan = -1
            var iconWhenTheNextOneRan: CustomIcon? = pack
            val next = launch(Dispatchers.Default) {
                wiring.homeLayoutRepository.whileProfileStays {
                    ShortcutPinLock.withLock {
                        rowsWhenTheNextOneRan = wiring.dao.getContainer(HOME).size
                        iconWhenTheNextOneRan = wiring.settingsRepository.shortcutIcons.first()[key]
                    }
                }
            }
            release.complete(Unit)
            awaitUntil("the next one") { next.isCompleted }
            holder.join()

            // It ran after the removal (the row was gone), and found the icon gone with it.
            assertThat(rowsWhenTheNextOneRan).isEqualTo(0)
            assertThat(iconWhenTheNextOneRan).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
