package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #18: the home page switch felt slow, so Settings > Home gets a speed choice. The choice is a
 * spring stiffness for the pager's snap and for every programmatic page change; an unknown stored
 * value behaves like the default so an old backup can never freeze the pager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PageSwitchSpeedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theSettingMapsToASpringStiffness() {
        assertThat(pageSwitchSpec(LauncherSettings.SPEED_NORMAL).stiffness).isEqualTo(Spring.StiffnessMediumLow)
        assertThat(pageSwitchSpec(LauncherSettings.SPEED_FAST).stiffness).isEqualTo(Spring.StiffnessMedium)
        assertThat(pageSwitchSpec(LauncherSettings.SPEED_FASTEST).stiffness).isEqualTo(Spring.StiffnessHigh)
        assertThat(pageSwitchSpec("garbage").stiffness).isEqualTo(Spring.StiffnessMediumLow)
    }

    @Test fun theFastestSettingSettlesAPageChangeWithinAQuarterSecond() {
        assertThat(settledAfter(LauncherSettings.SPEED_FASTEST, millis = 250)).isTrue()
    }

    @Test fun theNormalSettingIsStillMovingAQuarterSecondIn() {
        assertThat(settledAfter(LauncherSettings.SPEED_NORMAL, millis = 250)).isFalse()
    }

    /** Whether a real pager's page change with [speed] has come to rest [millis] after it started. */
    private fun settledAfter(speed: String, millis: Long): Boolean {
        lateinit var pagerState: PagerState
        lateinit var scope: CoroutineScope
        compose.mainClock.autoAdvance = false
        compose.setContent {
            pagerState = rememberPagerState { 3 }
            scope = rememberCoroutineScope()
            HorizontalPager(state = pagerState, modifier = Modifier.size(360.dp, 640.dp)) { Box(Modifier.fillMaxSize()) }
        }
        compose.mainClock.advanceTimeByFrame()
        scope.launch { pagerState.animateScrollToPage(1, animationSpec = pageSwitchSpec(speed)) }
        compose.mainClock.advanceTimeBy(millis)
        return pagerState.currentPage == 1 && pagerState.currentPageOffsetFraction == 0f && !pagerState.isScrollInProgress
    }
}
