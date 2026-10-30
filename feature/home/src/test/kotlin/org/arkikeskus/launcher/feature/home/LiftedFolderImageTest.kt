package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host's floating copy of a lifted folder keeps the tile's look through the drag: the icon rides
 * centred under the finger, and on home its name hangs below the icon in its own room (a copy boxed
 * to the icon's height cut the label to nothing while the names were on).
 *
 * The test API maps a node's position through the copy's lift (a graphicsLayer scale) but not its
 * size, so the geometry below is read from edges, scaled by the lift, never from reported centres.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class LiftedFolderImageTest {
    @get:Rule val compose = createComposeRule()

    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val chat = AppItem("com.chat", "chat.Main", Process.myUserHandle(), 0L, "Chat")
    private val tools = PlacedFolder(id = 7L, name = "Tools", apps = listOf(mail, chat), page = 0, cellX = 0, cellY = 0)
    private val lift = 1.1f

    private fun show(showLabel: Boolean) {
        compose.setContent {
            val finger = with(LocalDensity.current) { Offset(150.dp.toPx(), 200.dp.toPx()) }
            Box(Modifier.size(300.dp, 400.dp)) {
                LiftedFolderImage(
                    folder = tools, finger = { finger }, iconScale = 1f, tileWidth = 75.dp,
                    showLabel = showLabel, showName = true, labelColor = Color.White,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun aHomeCopyKeepsItsNameBelowTheIconUnderTheFinger() {
        show(showLabel = true)
        val label = compose.onNodeWithText("Tools")
        label.assertIsDisplayed()
        label.assertHeightIsAtLeast(10.dp)
        val b = label.getUnclippedBoundsInRoot()
        // Its top under the 52 dp icon's lower half plus the 4 dp gap; under the icon, not at the
        // tile's edge (loosely: Robolectric's text metrics are stand-ins, a few dp wide at most).
        assertThat(b.top.value).isWithin(1.5f).of(200f + 30f * lift)
        assertThat(((b.left + b.right) / 2).value).isWithin(6f).of(150f)
    }

    @Test fun aDockCopyCentresItsIconOnTheFinger() {
        show(showLabel = false)
        val icon = compose.onNodeWithContentDescription("Tools")
        icon.assertIsDisplayed()
        val b = icon.getUnclippedBoundsInRoot()
        // The 52 dp icon's top-left corner: half an icon up and left of the finger.
        assertThat(b.left.value).isWithin(1.5f).of(150f - 26f * lift)
        assertThat(b.top.value).isWithin(1.5f).of(200f - 26f * lift)
    }
}
