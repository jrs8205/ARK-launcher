package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.LocalSystemBarCovers
import org.arkikeskus.launcher.ui.SystemBarCovers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A Telegram request after 1.2.0: the home screen's open folder opened in the middle of the screen over a dark
 * scrim, with a look of its own, while the app drawer's opens by the tapped icon. The home screen's card now
 * opens and looks like the drawer's: by its folder's icon with a caret, as wide, its name as the title, and
 * nothing darkened behind it. The pencil stays (on the home screen a hold on the folder is a drag).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h800dp")
class FolderCardAnchorTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val user = Process.myUserHandle()
    private val apps = (1..8).map { AppItem("com.app$it", "Main", user, 0L, "App $it") }
    private val folder = PlacedFolder(id = 7, name = "Tools", apps = apps, page = 0, cellX = 0, cellY = 0)
    private val covers = SystemBarCovers()

    private fun show(anchorBounds: Rect?, folder: PlacedFolder = this.folder) {
        compose.setContent {
            CompositionLocalProvider(LocalSystemBarCovers provides covers) {
                LauncherTheme {
                    Box(Modifier.fillMaxSize()) {
                        FolderCard(
                            folder = folder, badges = emptyMap(), badgeShowCount = false, badgeScale = 1f, locked = false,
                            dragController = HomeDragController(), onRename = {}, onAppClick = {},
                            onRenameApp = {}, onRemoveFromFolder = {}, onPinShortcut = {}, onReorder = { _, _ -> },
                            onDropOnHome = { _, _, _, _ -> }, onDropOnDock = { _, _ -> }, onDropOnRemove = {},
                            onDragOutEnd = {}, onDismiss = {}, anchorBounds = anchorBounds,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun card() = compose.onNodeWithTag("folderCard").fetchSemanticsNode().boundsInRoot
    private fun carets() = compose.onAllNodesWithTag("popup-caret", useUnmergedTree = true).fetchSemanticsNodes()

    /** The folder's icon in the upper half of the 400 x 800 window. */
    private val upperIcon = Rect(left = 160f, top = 200f, right = 240f, bottom = 290f)

    /** The folder's icon in the lower half. */
    private val lowerIcon = Rect(left = 160f, top = 660f, right = 240f, bottom = 750f)

    @Test fun aFolderInTheUpperHalfOpensItsCardBelowItsIcon() {
        show(upperIcon)

        assertThat(card().top).isAtLeast(upperIcon.bottom)
        assertThat(card().top - upperIcon.bottom).isLessThan(60f)
        assertThat(card().center.x).isWithin(1f).of(upperIcon.center.x)
    }

    @Test fun aFolderInTheLowerHalfOpensItsCardAboveItsIcon() {
        show(lowerIcon)

        assertThat(card().bottom).isAtMost(lowerIcon.top)
        assertThat(lowerIcon.top - card().bottom).isLessThan(60f)
    }

    @Test fun theCaretPointsAtTheIcon() {
        show(upperIcon)

        val caret = carets().single().boundsInRoot
        assertThat(caret.center.x).isWithin(2f).of(upperIcon.center.x)
        assertThat(caret.bottom).isAtMost(card().top + 1f)
    }

    /** By an icon at the screen's edge the card stays on the screen, and the caret still points at the icon. */
    @Test fun byAnIconAtTheEdgeTheCardStaysOnTheScreen() {
        val edgeIcon = Rect(left = 10f, top = 200f, right = 90f, bottom = 290f)
        show(edgeIcon)

        assertThat(card().left).isAtLeast(0f)
        assertThat(card().right).isAtMost(400f)
        assertThat(carets().single().boundsInRoot.center.x).isWithin(2f).of(edgeIcon.center.x)
    }

    @Test fun theCardIsAsWideAsTheDrawersFolderCard() {
        show(upperIcon)

        assertThat(card().width).isWithin(0.5f).of(328f)
    }

    /** The view's header is the folder's name, as on the drawer's card, with the pencil at its end: no preview
     *  of the folder's icon before the name. */
    @Test fun theHeaderIsTheNameAndThePencil() {
        show(upperIcon)

        val name = compose.onNodeWithText("Tools").fetchSemanticsNode().boundsInRoot
        assertThat(name.left - card().left).isLessThan(24f)
        compose.onNodeWithContentDescription(context.getString(R.string.folder_edit)).assertExists()
    }

    /** Nothing is darkened behind the card, so the system bars' glyphs are told of no scrim. */
    @Test fun theOpenFolderDarkensNothingBehindIt() {
        show(upperIcon)

        assertThat(covers.homeDim).isEqualTo(0f)
    }

    /** A full folder's card would be taller than the room on its icon's side: it takes that room and its apps
     *  scroll, so the card never lies over the icon it opened from (its caret would point at nothing). */
    @Test fun aFullFoldersCardStillClearsItsIcon() {
        val full = folder.copy(apps = (1..24).map { AppItem("com.app$it", "Main", user, 0L, "App $it") })

        show(lowerIcon.translate(0f, -200f), full) // the icon's top at 460 of 800
        assertThat(card().bottom).isAtMost(460f)
        assertThat(card().top).isAtLeast(0f)
        // The caret keeps its place between the card and the icon: the card does not take its room.
        val caret = carets().single().boundsInRoot
        assertThat(caret.height).isWithin(0.5f).of(10f)
        assertThat(caret.top).isAtLeast(card().bottom - 1f)
        assertThat(caret.bottom).isAtMost(460f)
    }

    @Test fun aFullFoldersCardBelowItsIconStaysOnTheScreen() {
        val full = folder.copy(apps = (1..24).map { AppItem("com.app$it", "Main", user, 0L, "App $it") })

        show(upperIcon, full)
        assertThat(card().top).isAtLeast(upperIcon.bottom)
        assertThat(card().bottom).isAtMost(800f)
    }

    /** No icon to open by: the card is in the middle of the window and has no caret. */
    @Test fun withoutAnIconToOpenByTheCardIsInTheMiddle() {
        show(anchorBounds = null)

        assertThat(card().center.x).isWithin(1f).of(200f)
        assertThat(card().center.y).isWithin(1f).of(400f)
        assertThat(carets()).isEmpty()
    }
}
