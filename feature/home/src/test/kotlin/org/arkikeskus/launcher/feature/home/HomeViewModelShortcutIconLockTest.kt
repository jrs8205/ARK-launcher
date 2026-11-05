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
 * Issue #23: the icon picked for a pinned shortcut goes with the shortcut's last copy INSIDE the same
 * pin-lock section as the row. Cleared after the lock, a restore queued behind the removal could
 * re-file the shortcut with its icon first and then lose the icon to the late clearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeViewModelShortcutIconLockTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val pack = CustomIcon.FromPack("com.example.pack", "octocat")

    @Test fun theIconIsClearedBeforeTheNextHolderOfThePinLockRuns() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            wiring.publishShortcut("github")
            val row = wiring.dao.insert(HomeItemEntity(packageName = context.packageName, shortcutId = "github", page = 0, cellX = 0, cellY = 0))
            val key = ShortcutIconKey.of(context.packageName, "github", 0L)
            wiring.settingsRepository.setShortcutIcon(key, pack)
            val viewModel = wiring.homeViewModel()

            // Someone holds the lock (a restore, a pin): the removal queues behind it, and the holder
            // queued after the removal must find the icon gone along with the row.
            val holding = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.Default) { ShortcutPinLock.withLock { holding.complete(Unit); release.await() } }
            holding.await()
            viewModel.removeShortcut(row)
            shadowOf(Looper.getMainLooper()).idle()
            var iconWhenTheNextHolderRan: CustomIcon? = pack
            val next = launch(Dispatchers.Default) {
                ShortcutPinLock.withLock { iconWhenTheNextHolderRan = wiring.settingsRepository.shortcutIcons.first()[key] }
            }
            release.complete(Unit)
            awaitUntil("the next holder") { next.isCompleted }
            holder.join()

            assertThat(wiring.dao.getContainer(HOME)).isEmpty()
            assertThat(iconWhenTheNextHolderRan).isNull()
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
