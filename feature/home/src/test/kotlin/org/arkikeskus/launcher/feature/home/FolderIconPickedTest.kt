package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.CustomIconImage
import org.arkikeskus.launcher.model.IconEpochs
import org.arkikeskus.launcher.ui.component.LocalIconEpochs
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/** A folder with an icon of its own shows that icon instead of the preview of its first apps. */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FolderIconPickedTest {
    @get:Rule val compose = createComposeRule()

    private val apps = listOf("Mail", "Maps", "Notes").map { AppItem("com.$it", "Main", Process.myUserHandle(), 0L, it) }
    private val icon = CustomIcon.FromPack("com.example.pack", "folder_tools")

    @After fun resetImageLoader() = SingletonImageLoader.reset()

    private fun showFolder() = compose.setContent {
        FolderIcon(name = "Tools", apps = apps, showLabel = true, badgeCount = 0, badgeShowCount = false, customIcon = icon)
    }

    @Test fun withoutAnIconTheFolderPreviewsItsApps() {
        compose.setContent { FolderIcon(name = "Tools", apps = apps, showLabel = true, badgeCount = 0, badgeShowCount = false) }

        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(1)
    }

    @Test fun aPickedIconReplacesThePreview() {
        val loaded = AtomicBoolean(false)
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

        showFolder()
        compose.waitUntil(5_000) { loaded.get() }
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(0)
        compose.onNodeWithContentDescription("Tools").assertExists()
    }

    /** The pack was uninstalled (or the picture deleted): the folder shows its preview again, not a blank tile. */
    @Test fun aPickedIconThatCannotBeLoadedFallsBackToThePreview() {
        showFolder()

        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Mail").fetchSemanticsNodes().size == 1 }
    }

    /** The uninstalled pack comes back: its new icon epoch loads the folder's icon again in place of the preview. */
    @Test fun aPickedIconLoadsAgainWhenItsPackIsReinstalled() {
        val installed = AtomicBoolean(false)
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(RuntimeEnvironment.getApplication()).components {
                add(Fetcher.Factory<CustomIconImage> { _, _, _ ->
                    Fetcher {
                        check(installed.get()) { "pack not installed" }
                        ImageFetchResult(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImage(), false, DataSource.MEMORY)
                    }
                })
            }.build(),
        )
        var epochs by mutableStateOf(IconEpochs())
        compose.setContent {
            CompositionLocalProvider(LocalIconEpochs provides epochs) {
                FolderIcon(name = "Tools", apps = apps, showLabel = true, badgeCount = 0, badgeShowCount = false, customIcon = icon)
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Mail").fetchSemanticsNodes().size == 1 }

        installed.set(true)
        epochs = epochs.bumpGlobal()

        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Mail").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Tools").assertExists()
    }
}
