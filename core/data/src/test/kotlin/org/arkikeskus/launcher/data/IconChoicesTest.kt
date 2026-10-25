package org.arkikeskus.launcher.data

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.CustomIcon
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.StringReader

/**
 * The icon picker's sources, as in Nova Launcher and Lawnchair: every icon of an installed pack
 * (its drawable.xml list, or the icons its appfilter maps when it has none), the pack's own match for
 * the app first, and an image from the gallery shaped like the phone's other icons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconChoicesTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun parser(xml: String) = Xml.newPullParser().apply { setInput(StringReader(xml)) }

    @Test fun aPacksDrawableListNamesEveryIconOnceInItsOrder() {
        val names = parseDrawableList(
            parser(
                """
                <resources>
                    <version>1</version>
                    <category title="Google" />
                    <item drawable="google_chrome" />
                    <item drawable="google_maps" />
                    <category title="Other" />
                    <item drawable="calculator" />
                    <item drawable="google_chrome" />
                </resources>
                """.trimIndent(),
            ),
        )

        assertThat(names).containsExactly("google_chrome", "google_maps", "calculator").inOrder()
    }

    @Test fun thePacksOwnMatchForAnAppIsSuggested() {
        val pack = IconPack(
            "com.example.pack",
            context.resources,
            mapOf("com.android.chrome/com.google.android.apps.chrome.Main" to "google_chrome"),
        )

        assertThat(pack.drawableName(ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main"))).isEqualTo("google_chrome")
        assertThat(pack.drawableName(ComponentName("com.other", "Main"))).isNull()
        assertThat(pack.mappedDrawables()).containsExactly("google_chrome")
    }

    /** A gallery picture becomes a square icon cut to the phone's icon shape: the middle of the
     *  picture is kept, the corners outside the shape are clear. */
    @Test fun aGalleryImageBecomesAnIconShapedLikeThePhonesIcons() = runTest {
        val picture = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val file = File(context.cacheDir, "picture.png").apply { outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val files = CustomIconFiles(context)

        val icon = files.import(context, Uri.fromFile(file))

        assertThat(icon).isInstanceOf(CustomIcon.FromFile::class.java)
        val saved = files.load(icon as CustomIcon.FromFile)!!
        assertThat(saved.width).isEqualTo(CustomIconFiles.SIZE)
        assertThat(saved.height).isEqualTo(CustomIconFiles.SIZE)
        assertThat(saved.getPixel(CustomIconFiles.SIZE / 2, CustomIconFiles.SIZE / 2)).isEqualTo(Color.GREEN)
        assertThat(Color.alpha(saved.getPixel(0, 0))).isEqualTo(0)
    }

    @Test fun somethingThatIsNoImageGivesNoIcon() = runTest {
        val file = File(context.cacheDir, "notes.txt").apply { writeText("not an image") }

        assertThat(CustomIconFiles(context).import(context, Uri.fromFile(file))).isNull()
    }
}
