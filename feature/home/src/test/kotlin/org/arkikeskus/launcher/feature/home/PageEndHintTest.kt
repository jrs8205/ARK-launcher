package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PageEndHintTest {
    @get:Rule val compose = createComposeRule()

    private var bounces by mutableIntStateOf(0)

    @Test
    fun staysHiddenUntilTheFirstBounce() {
        compose.setContent { PageEndHint(bounces = { bounces }) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("No more pages").assertDoesNotExist()
    }

    @Test
    fun eachBounceSaysNoMorePagesBrieflyAndThenItGoes() {
        compose.setContent { PageEndHint(bounces = { bounces }) }

        bounces = 1
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("No more pages").assertExists()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithText("No more pages").assertDoesNotExist()

        bounces = 2
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("No more pages").assertExists()
    }
}
