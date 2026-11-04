package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The rows that decide what the app drawer paints over what is behind it (Settings > App drawer, the owner's 1.0.0
 * notes): the colour strip with "Transparent" first, the colour's opacity only while a colour is in effect, the own
 * picture with a dim of its own in place of the colour, and the blur switch whose "the drawer is solid" hint follows
 * what the drawer really paints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerBackdropRowsTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val blue = 0xFF42A5F5.toInt()
    private val dims = mutableListOf<Float>()
    private val picture = DrawerWallpaper(File.createTempFile("wallpaper", ".jpg").apply { deleteOnExit() }, luminance = 0.3f)

    private fun show(settings: LauncherSettings, wallpaper: DrawerWallpaper? = null) {
        compose.setContent {
            // Stacked in a scrolling column as the settings page stacks them.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DrawerBackdropRows(
                    settings = settings,
                    wallpaper = wallpaper,
                    blurAvailability = BlurAvailability.AVAILABLE,
                    actions = DrawerBackdropActions(onPictureDim = { dims += it }),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.onNodeWithText(res.getString(id))

    @Test fun aColourOffersItsOpacityAndNoDim() {
        show(LauncherSettings(drawerBackgroundColor = blue))

        text(R.string.settings_drawer_bg).assertIsDisplayed()
        text(R.string.settings_drawer_picture_dim).assertDoesNotExist()
        text(R.string.settings_drawer_color_locked).assertDoesNotExist()
        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsEnabled()
    }

    @Test fun theThemeCountsAsAColourToo() {
        show(LauncherSettings(drawerBackgroundColor = null))

        text(R.string.settings_drawer_bg).assertIsDisplayed()
        text(R.string.settings_drawer_picture_dim).assertDoesNotExist()
    }

    @Test fun transparentHidesTheOpacitySlider() {
        show(LauncherSettings(drawerBackgroundColor = LauncherSettings.DRAWER_COLOR_TRANSPARENT))

        text(R.string.settings_drawer_bg).assertDoesNotExist()
        text(R.string.settings_drawer_picture_dim).assertDoesNotExist()
    }

    /** With the user's own picture the colour is off and locked, and a dim of the picture takes the opacity's place
     *  under the picture row; the dim moves in 5 % steps like the other sliders. */
    @Test fun aPictureLocksTheColourAndOffersADimInstead() {
        show(LauncherSettings(drawerBackgroundColor = blue), wallpaper = picture)

        text(R.string.settings_drawer_bg).assertDoesNotExist()
        text(R.string.settings_drawer_color_locked).assertIsDisplayed()
        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsNotEnabled()
        text(R.string.settings_drawer_picture_dim).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(DRAWER_WALLPAPER_PREVIEW_TAG).assertExists()

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.32f) }

        assertThat(dims.single()).isWithin(0.001f).of(0.30f)
    }

    /** The blur switch's "the drawer is solid" hint speaks of what the drawer really paints: not for "Transparent" at
     *  a 100 % opacity, which paints nothing; yes for a picture dimmed all the way, which is black. */
    @Test fun theBlurHintCountsWhatTheDrawerReallyPaints() {
        show(
            LauncherSettings(
                blurBehindDrawer = true,
                drawerBackgroundColor = LauncherSettings.DRAWER_COLOR_TRANSPARENT,
                drawerBackgroundOpacity = 1f,
            ),
        )
        text(R.string.settings_blur_behind_drawer_opaque).assertDoesNotExist()
        text(R.string.settings_blur_behind_drawer_desc).performScrollTo().assertIsDisplayed()
    }

    @Test fun aPictureDimmedAllTheWayIsASolidDrawerToTheBlurHint() {
        show(LauncherSettings(blurBehindDrawer = true, drawerBackgroundOpacity = 0.5f, drawerPictureDim = 1f), wallpaper = picture)

        text(R.string.settings_blur_behind_drawer_opaque).performScrollTo().assertIsDisplayed()
    }
}
