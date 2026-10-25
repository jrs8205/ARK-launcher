package org.arkikeskus.launcher.ui

import android.app.Application
import android.net.Uri
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.IconChoices
import org.arkikeskus.launcher.model.IconPackChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Edit" on an app, as in Lawnchair and Nova Launcher: the dialog shows the app's icon and name; a
 * tap on the icon opens the picker — the original icon, a gallery picture, or an icon from an
 * installed pack with the pack's own match first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class EditAppDialogTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val app = AppItem("com.android.chrome", "Main", Process.myUserHandle(), 0L, "Chrome")
    private val saved = mutableListOf<Pair<String?, CustomIcon?>>()
    private var resets = 0
    private var dismissed = 0

    private val choices = object : IconChoices {
        val discarded = mutableListOf<CustomIcon>()
        override fun packs() = listOf(IconPackChoice("com.example.pack", "Example Icons"))
        override fun icons(pack: String) = listOf("calculator", "google_chrome", "google_maps")
        override fun suggestion(pack: String, app: AppItem) = "google_chrome".takeIf { app.packageName == "com.android.chrome" }
        override fun importImage(uri: Uri): CustomIcon? = CustomIcon.FromFile("imported.png")
        override fun discard(icon: CustomIcon) {
            discarded += icon
        }
    }

    private fun show(target: AppItem = app) {
        compose.setContent {
            CompositionLocalProvider(LocalIconChoices provides choices) {
                EditAppDialog(
                    app = target,
                    onSave = { name, icon -> saved += name to icon },
                    onReset = { resets++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = res.getString(id)

    private fun openPicker() {
        compose.onNodeWithContentDescription(text(R.string.icon_change)).performClick()
        compose.waitForIdle()
    }

    @Test fun savingKeepsTheNameAndAPackIconPicked() {
        show()

        openPicker()
        compose.onNodeWithText("Example Icons").performClick()
        compose.waitForIdle()
        // The pack's own match for Chrome comes first, marked.
        compose.onNodeWithText(text(R.string.icon_suggested)).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("google maps")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.rename_save)).performClick()

        // The name was left as it was: none is saved, so a later rename by the app still shows.
        assertThat(saved).containsExactly(null to CustomIcon.FromPack("com.example.pack", "google_maps"))
        assertThat(dismissed).isEqualTo(1)
    }

    @Test fun theSearchNarrowsThePacksIcons() {
        show()

        openPicker()
        compose.onNodeWithText("Example Icons").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.icon_search)).performTextInput("calc")
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription("calculator")[0].assertExists()
        compose.onAllNodesWithContentDescription("google maps").fetchSemanticsNodes().let { assertThat(it).isEmpty() }
    }

    /** The grid keys its tiles: a drawable named like the suggestion's own key must not clash with it. */
    @Test fun aPackIconNamedLikeTheSuggestionsKeyStillShows() {
        val packWithClash = object : IconChoices by choices {
            override fun icons(pack: String) = listOf("google_chrome", "suggested")
        }
        compose.setContent {
            CompositionLocalProvider(LocalIconChoices provides packWithClash) {
                EditAppDialog(app = app, onSave = { _, _ -> }, onReset = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()

        openPicker()
        compose.onNodeWithText("Example Icons").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.icon_suggested)).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("suggested")[0].assertExists()
    }

    @Test fun aTypedNameIsSaved() {
        show()

        compose.onNodeWithText("Chrome").performTextReplacement("Browser")
        compose.onNodeWithText(text(R.string.rename_save)).performClick()

        assertThat(saved).containsExactly("Browser" to null)
    }

    @Test fun theOriginalIconClearsAPickedOne() {
        show(app.copy(customIcon = CustomIcon.FromPack("com.example.pack", "calculator")))

        openPicker()
        compose.onNodeWithText(text(R.string.icon_original)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.rename_save)).performClick()

        assertThat(saved).containsExactly(null to null)
    }

    @Test fun resetBringsBackTheOriginalNameAndIcon() {
        show(app.copy(customIcon = CustomIcon.FromPack("com.example.pack", "calculator")))

        compose.onNodeWithText(text(R.string.rename_reset)).performClick()

        assertThat(resets).isEqualTo(1)
        assertThat(saved).isEmpty()
    }

    @Test fun withoutIconPacksThePickerSaysSo() {
        val none = object : IconChoices by choices {
            override fun packs() = emptyList<IconPackChoice>()
        }
        compose.setContent {
            CompositionLocalProvider(LocalIconChoices provides none) {
                EditAppDialog(app = app, onSave = { _, _ -> }, onReset = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()

        openPicker()

        compose.onNodeWithText(text(R.string.icon_packs_none)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.icon_from_gallery)).assertIsDisplayed()
    }
}
