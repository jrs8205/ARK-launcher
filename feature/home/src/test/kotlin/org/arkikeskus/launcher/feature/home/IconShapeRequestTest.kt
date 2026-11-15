package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.CustomIconImage
import org.arkikeskus.launcher.model.IconRequest
import org.arkikeskus.launcher.model.IconShape
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.LocalIconShape
import org.arkikeskus.launcher.ui.component.PickedIcon
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** The icon shape a surface provides reaches what the image loader is asked for (issue #26). */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class IconShapeRequestTest {
    @get:Rule val compose = createComposeRule()

    private val app = AppItem("com.mail", "Main", Process.myUserHandle(), 0L, "Mail")
    private val apps = CopyOnWriteArrayList<IconRequest>()
    private val pictures = CopyOnWriteArrayList<CustomIconImage>()

    private fun blank() = ImageFetchResult(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImage(), false, DataSource.MEMORY)

    @Before fun noteWhatIsAskedFor() {
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(RuntimeEnvironment.getApplication()).components {
                add(Fetcher.Factory<IconRequest> { data, _, _ -> Fetcher { apps += data; blank() } })
                add(Fetcher.Factory<CustomIconImage> { data, _, _ -> Fetcher { pictures += data; blank() } })
            }.build(),
        )
    }

    @After fun resetImageLoader() = SingletonImageLoader.reset()

    private fun show(shape: IconShape?, content: @Composable () -> Unit) {
        compose.setContent {
            if (shape == null) content() else CompositionLocalProvider(LocalIconShape provides shape) { content() }
        }
    }

    @Test fun anAppIconIsAskedForInTheShapeOfItsSurface() {
        show(IconShape.HEXAGON) { AppIcon(app, labelColor = Color.White) }
        compose.waitUntil(5_000) { apps.isNotEmpty() }

        assertThat(apps.map { it.shape }.distinct()).containsExactly(IconShape.HEXAGON)
    }

    @Test fun whereNoShapeIsProvidedAnAppIconIsAskedForInTheSystems() {
        show(null) { AppIcon(app, labelColor = Color.White) }
        compose.waitUntil(5_000) { apps.isNotEmpty() }

        assertThat(apps.map { it.shape }.distinct()).containsExactly(IconShape.SYSTEM)
    }

    @Test fun aPickedPictureIsAskedForInTheShapeToo() {
        show(IconShape.TEARDROP) { PickedIcon(CustomIcon.FromFile("a.png"), 48.dp, contentDescription = "Tools") }
        compose.waitUntil(5_000) { pictures.isNotEmpty() }

        assertThat(pictures.map { it.shape }.distinct()).containsExactly(IconShape.TEARDROP)
    }

    @Test fun theSmallIconsInsideAFolderTakeTheShape() {
        show(IconShape.SQUARE) {
            FolderIcon(name = "Tools", apps = listOf(app), showLabel = false, badgeCount = 0, badgeShowCount = false)
        }
        compose.waitUntil(5_000) { apps.isNotEmpty() }

        assertThat(apps.map { it.shape }.distinct()).containsExactly(IconShape.SQUARE)
    }
}
