package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.FolderStyle
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The open folder's look (a Telegram request after 1.0.4): app names on or off, the icons' size,
 * the spacing between the rows and a background colour of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FolderCardStyleTest {
    @get:Rule val compose = createComposeRule()

    private val user = Process.myUserHandle()
    private val apps = (1..8).map { AppItem("com.app$it", "Main", user, 0L, "App $it") }
    private val folder = PlacedFolder(id = 7, name = "Tools", apps = apps, page = 0, cellX = 0, cellY = 0)
    private var style by mutableStateOf(FolderStyle())
    private val launched = mutableListOf<AppItem>()

    private fun show() {
        compose.setContent {
            LauncherTheme {
                Box(Modifier.fillMaxSize()) {
                    FolderCard(
                        folder = folder, badges = emptyMap(), badgeShowCount = false, badgeScale = 1f, locked = false,
                        dragController = HomeDragController(), onRename = {}, onAppClick = { launched += it },
                        onRenameApp = {}, onRemoveFromFolder = {}, onPinShortcut = {}, onReorder = { _, _ -> },
                        onDropOnHome = { _, _, _, _ -> }, onDropOnDock = { _, _ -> }, onDropOnRemove = {},
                        onDragOutEnd = {}, onDismiss = {}, style = style,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun gridHeight() = compose.onNodeWithTag("folderGrid").fetchSemanticsNode().size.height
    private fun iconHeight() = compose.onNodeWithContentDescription("App 1").fetchSemanticsNode().size.height

    @Test fun withoutAppNamesTheIconsStayAndStillOpenTheirApps() {
        show()
        compose.onNodeWithText("App 1").assertExists()

        style = FolderStyle(showLabels = false)
        compose.waitForIdle()

        assertThat(compose.onAllNodesWithText("App 1").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithContentDescription("App 1").performClick()
        compose.waitForIdle()
        assertThat(launched.map { it.label }).containsExactly("App 1")
    }

    @Test fun withoutAppNamesTheRowsAreShorter() {
        show()
        val withNames = gridHeight()

        style = FolderStyle(showLabels = false)
        compose.waitForIdle()

        assertThat(gridHeight()).isLessThan(withNames)
    }

    @Test fun theIconSizeSettingScalesTheIcons() {
        show()
        val normal = iconHeight()

        style = FolderStyle(iconScale = 0.7f)
        compose.waitForIdle()

        assertThat(iconHeight()).isLessThan(normal)
    }

    @Test fun theSpacingSettingPacksTheRowsTighterOrLooser() {
        show()
        val normal = gridHeight()

        style = FolderStyle(spacing = 0.5f)
        compose.waitForIdle()
        val tight = gridHeight()
        style = FolderStyle(spacing = 1.5f)
        compose.waitForIdle()

        assertThat(tight).isLessThan(normal)
        assertThat(gridHeight()).isGreaterThan(normal)
    }

    /** No colour chosen and solid: the theme's surface and its text colour, as before. Anything else
     *  takes black or white text by the contrast with what the card looks like over the dimmed home
     *  screen (the wallpaper under the folder's 50 % black scrim). */
    @Test fun theCardColours() {
        val surface = Color(0xFFFBFCFF)
        val onSurface = Color(0xFF1A1C1E)
        val blackWallpaper = 0xFF000000.toInt()
        val whiteWallpaper = 0xFFFFFFFF.toInt()
        assertThat(folderCardColors(FolderStyle(), surface, onSurface, blackWallpaper)).isEqualTo(FolderCardColors(surface, onSurface))

        val black = folderCardColors(FolderStyle(backgroundColor = 0xFF000000.toInt()), surface, onSurface, whiteWallpaper)
        assertThat(black).isEqualTo(FolderCardColors(Color.Black, Color.White))
        // The offered blue, solid: black text reads far better than white (7.9:1 against 2.65:1).
        assertThat(folderCardColors(FolderStyle(backgroundColor = 0xFF42A5F5.toInt()), surface, onSurface, blackWallpaper).content)
            .isEqualTo(Color.Black)
        // A faint light card over the dimmed home screen is dark: light text.
        assertThat(folderCardColors(FolderStyle(backgroundColor = 0xFFFFFFFF.toInt(), backgroundOpacity = 0.25f), surface, onSurface, blackWallpaper).content)
            .isEqualTo(Color.White)
    }

    /** The theme's own light surface at 20 % over a black wallpaper is a nearly black card: its text
     *  must turn light too, not stay the theme's dark one. */
    @Test fun theThemesSurfaceMadeSeeThroughTakesReadableText() {
        val surface = Color(0xFFFBFCFF)
        val onSurface = Color(0xFF1A1C1E)
        val faint = folderCardColors(FolderStyle(backgroundOpacity = 0.2f), surface, onSurface, wallpaper = 0xFF000000.toInt())
        assertThat(faint.container).isEqualTo(surface.copy(alpha = 0.2f))
        assertThat(faint.content).isEqualTo(Color.White)
        // Nearly solid it is still a light card.
        assertThat(folderCardColors(FolderStyle(backgroundOpacity = 0.9f), surface, onSurface, wallpaper = 0xFF000000.toInt()).content)
            .isEqualTo(Color.Black)
    }
}
