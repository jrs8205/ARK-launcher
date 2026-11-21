package org.arkikeskus.launcher.ui

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.data.ShownDrawerWallpaper
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Test
import java.io.File

/**
 * Review round 10. The system bars' glyph colour over an open drawer is counted from what the drawer paints,
 * the user's own picture included. The store of the pictures follows a profile switch a moment late, and the
 * settings were joined with whatever picture it showed: for that moment the bars were coloured for the previous
 * profile's picture. The picture counts only while it is the picture of the profile the settings are of.
 */
class StatusBarSettingsTest {
    private val picture = DrawerWallpaper(File("picture.jpg"), luminance = 0.9f)

    @Test fun thePictureOfTheProfileTheSettingsAreOfCounts() {
        val bars = statusBarSettings(LauncherSettings(homeProfileId = 2), ShownDrawerWallpaper(profile = 2, picture = picture))

        assertThat(bars.drawerPicture).isEqualTo(picture)
    }

    @Test fun anotherProfilesPictureIsNoPicture() {
        val settings = LauncherSettings(homeProfileId = 2)

        val bars = statusBarSettings(settings, ShownDrawerWallpaper(profile = 1, picture = picture))

        assertThat(bars.drawerPicture).isNull()
        // And the drawer's surface is the one without a picture: as with no picture set at all.
        assertThat(bars).isEqualTo(statusBarSettings(settings, ShownDrawerWallpaper(profile = 2, picture = null)))
    }
}
