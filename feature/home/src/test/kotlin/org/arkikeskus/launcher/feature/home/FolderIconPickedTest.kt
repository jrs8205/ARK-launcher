package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A folder with an icon of its own shows that icon instead of the preview of its first apps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FolderIconPickedTest {
    @get:Rule val compose = createComposeRule()

    private val apps = listOf("Mail", "Maps", "Notes").map { AppItem("com.$it", "Main", Process.myUserHandle(), 0L, it) }

    @Test fun withoutAnIconTheFolderPreviewsItsApps() {
        compose.setContent { FolderIcon(name = "Tools", apps = apps, showLabel = true, badgeCount = 0, badgeShowCount = false) }

        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(1)
    }

    @Test fun aPickedIconReplacesThePreview() {
        compose.setContent {
            FolderIcon(
                name = "Tools",
                apps = apps,
                showLabel = true,
                badgeCount = 0,
                badgeShowCount = false,
                customIcon = CustomIcon.FromPack("com.example.pack", "folder_tools"),
            )
        }

        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(0)
        compose.onNodeWithContentDescription("Tools").assertExists()
    }
}
