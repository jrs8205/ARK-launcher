package org.arkikeskus.launcher.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Process
import coil3.request.Options
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.CustomIconImage
import org.arkikeskus.launcher.model.IconRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Loading a picked icon: a gallery image saved on this phone, or a drawable of an icon pack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomIconSourceTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val source = CustomIconSource(context, IconPackRepository(context))
    private val app = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")

    private fun square(color: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun aSavedImageLoadsBackAndGoesWhenForgotten() {
        val icon = source.files.save(square(Color.RED))

        val drawable = source.drawable(icon)
        assertThat(drawable).isInstanceOf(BitmapDrawable::class.java)
        assertThat((drawable as BitmapDrawable).bitmap.getPixel(32, 32)).isEqualTo(Color.RED)

        source.files.delete(icon)
        assertThat(source.drawable(icon)).isNull()
    }

    @Test fun eachSavedImageGetsANameOfItsOwn() {
        assertThat(source.files.save(square(Color.RED))).isNotEqualTo(source.files.save(square(Color.BLUE)))
    }

    @Test fun anIconOfAPackThatIsNotInstalledIsNone() {
        assertThat(source.drawable(CustomIcon.FromPack("com.example.gone", "mail"))).isNull()
    }

    /** The picked icon is part of the icon's cache key: picking one shows it at once, everywhere. */
    @Test fun aPickedIconChangesTheCacheKey() {
        val keyer = AppIconKeyer()
        val options = Options(context)
        val plain = keyer.key(IconRequest(app), options)
        val picked = keyer.key(IconRequest(app.copy(customIcon = CustomIcon.FromPack("p", "a"))), options)
        val other = keyer.key(IconRequest(app.copy(customIcon = CustomIcon.FromPack("p", "b"))), options)

        assertThat(setOf(plain, picked, other)).hasSize(3)
        assertThat(CustomIconKeyer().key(CustomIconImage(CustomIcon.FromFile("a.png")), options))
            .isNotEqualTo(CustomIconKeyer().key(CustomIconImage(CustomIcon.FromFile("b.png")), options))
    }
}
