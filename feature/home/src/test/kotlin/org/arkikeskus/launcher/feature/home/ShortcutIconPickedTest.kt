package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.CustomIconImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issue #23: a pinned shortcut with an icon of its own (a browser's web shortcut dressed from an icon
 * pack) shows that icon instead of the one the app pinned it with; without one, or when the picked
 * icon cannot be loaded, the pinned icon stays.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ShortcutIconPickedTest {
    @get:Rule val compose = createComposeRule()

    private val pinned = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val picked = CustomIcon.FromPack("com.example.pack", "octocat")

    private fun shortcut(customIcon: CustomIcon?) = PlacedShortcut(
        rowId = 1, packageName = "org.mozilla.firefox", shortcutId = "https://github.com/", userSerial = 0L, label = "GitHub",
        icon = pinned, enabled = true, disabledMessage = null, page = 0, cellX = 0, cellY = 0, customIcon = customIcon,
    )

    @After fun resetImageLoader() = SingletonImageLoader.reset()

    private fun fetcherThatLoads(loaded: AtomicBoolean) {
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(RuntimeEnvironment.getApplication()).components {
                add(Fetcher.Factory<CustomIconImage> { _, _, _ ->
                    Fetcher {
                        loaded.set(true)
                        ImageFetchResult(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImage(), false, DataSource.MEMORY)
                    }
                })
            }.build(),
        )
    }

    @Test fun withoutAnIconOfItsOwnTheShortcutShowsThePinnedOne() {
        val loaded = AtomicBoolean(false)
        fetcherThatLoads(loaded)

        compose.setContent { ShortcutIconContent(shortcut(customIcon = null), showLabel = true) }
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription("GitHub").assertCountEquals(1)
        assert(!loaded.get()) { "no picked icon, nothing to load" }
    }

    @Test fun aPickedIconIsLoadedInPlaceOfThePinnedOne() {
        val loaded = AtomicBoolean(false)
        fetcherThatLoads(loaded)

        compose.setContent { ShortcutIconContent(shortcut(customIcon = picked), showLabel = true) }
        compose.waitUntil(5_000) { loaded.get() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("GitHub").assertExists()
    }

    /** The pack was uninstalled (or the picture deleted): the pinned icon is back, not a blank tile. */
    @Test fun aPickedIconThatCannotBeLoadedFallsBackToThePinnedOne() {
        compose.setContent { ShortcutIconContent(shortcut(customIcon = picked), showLabel = true) }
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription("GitHub").assertCountEquals(1)
    }
}
