package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.IconShape
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The icon shape row (issue #26): one tile per shape, each drawn in its shape, the chosen one marked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconShapeRowTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val picked = mutableListOf<IconShape>()

    private fun show(selected: IconShape) {
        compose.setContent { IconShapeRow(selected = selected, onPick = { picked += it }) }
        compose.waitForIdle()
    }

    @Test fun everyShapeIsOfferedAndTheSystemsIsMarked() {
        show(IconShape.SYSTEM)

        compose.onNodeWithText(res.getString(R.string.settings_icon_shape)).assertIsDisplayed()
        IconShape.entries.forEach { compose.onNodeWithTag(iconShapeTag(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag(iconShapeTag(IconShape.SYSTEM)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(iconShapeTag(IconShape.HEXAGON)).performScrollTo().assertIsNotSelected()
        assertThat(IconShape.entries).hasSize(9)
    }

    @Test fun aTilePicksItsShape() {
        show(IconShape.SYSTEM)

        compose.onNodeWithTag(iconShapeTag(IconShape.SQUARE)).performScrollTo().performClick()
        compose.onNodeWithTag(iconShapeTag(IconShape.FLOWER)).performScrollTo().performClick()

        assertThat(picked).containsExactly(IconShape.SQUARE, IconShape.FLOWER).inOrder()
    }

    @Test fun theChosenShapeIsMarked() {
        show(IconShape.TEARDROP)

        compose.onNodeWithTag(iconShapeTag(IconShape.TEARDROP)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(iconShapeTag(IconShape.SYSTEM)).performScrollTo().assertIsNotSelected()
    }

    @Test fun everyTileSaysItsShapesName() {
        show(IconShape.SYSTEM)

        for (name in listOf("System", "Circle", "Squircle", "Rounded square", "Square", "Teardrop", "Hexagon", "Octagon", "Flower")) {
            compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun theRowSaysThatAnIconPacksIconsKeepTheirShape() {
        show(IconShape.SYSTEM)

        compose.onNodeWithText(res.getString(R.string.settings_icon_shape_desc)).assertIsDisplayed()
    }

    @Test fun onTheIconsPageATileStoresTheShape() {
        val actions = RecordedActions()
        compose.setContent { IconsRows(LauncherSettings(), iconPacks = emptyList(), actions = actions.actions) }

        compose.onNodeWithTag(iconShapeTag(IconShape.OCTAGON)).performScrollTo().performClick()

        assertThat(actions.calls).contains("setIconShape" to listOf<Any?>(IconShape.OCTAGON))
    }
}
