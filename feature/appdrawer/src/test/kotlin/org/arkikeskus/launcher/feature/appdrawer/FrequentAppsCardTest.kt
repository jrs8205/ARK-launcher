package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Process
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.expressive.LightExpressivePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "most used" card sits on the expressive palette's solid surface, so its labels take that palette's text even
 * while the see-through drawer around it has flipped its text to white over a dark picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FrequentAppsCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theLabelsFollowTheCardsOwnSurfaceNotTheDrawersFlippedText() {
        val app = AppItem(packageName = "com.mail", className = "Main", user = Process.myUserHandle(), userSerial = 0, label = "Mail")
        compose.setContent {
            // A light theme whose see-through drawer, over a dark picture, has turned its text white.
            MaterialTheme(colorScheme = drawerColorScheme(lightColorScheme(), background = null, textIsDark = false)) {
                DrawerExpressiveTheme(background = null) {
                    FrequentAppsCard(
                        apps = listOf(app),
                        columns = 4,
                        badges = emptyMap(),
                        badgeShowCount = false,
                        badgeScale = 1f,
                        showLabels = true,
                        iconSize = 56.dp,
                        onAppClick = {},
                        onAppLongClick = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()

        val node = compose.onAllNodesWithText("Mail", useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertThat(results.single().layoutInput.style.color).isEqualTo(LightExpressivePalette.text)
    }
}
