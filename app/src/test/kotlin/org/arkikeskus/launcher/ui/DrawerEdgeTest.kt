package org.arkikeskus.launcher.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The drawer's soft edge while it opens and closes (0 = closed, 1 = open): round top corners and a
 * shadow while it moves, square and shadowless once open, and home behind it dimming as it rises.
 */
class DrawerEdgeTest {

    @Test fun cornersAreRoundWhileTheDrawerMoves() {
        for (p in listOf(0f, 0.05f, 0.3f, 0.5f, 0.8f, 0.85f)) {
            assertThat(drawerCornerFraction(p)).isWithin(1e-5f).of(1f)
        }
    }

    @Test fun cornersSquareOffOverTheLastStretchSoTheOpenDrawerFillsTheScreen() {
        assertThat(drawerCornerFraction(1f)).isEqualTo(0f)
        assertThat(drawerCornerFraction(0.925f)).isWithin(0.01f).of(0.5f)
        val samples = (85..100).map { drawerCornerFraction(it / 100f) }
        assertThat(samples).isInOrder(Comparator.reverseOrder<Float>())
    }

    /** A shadow on a drawer parked just below the screen would bleed a dark line onto the dock. */
    @Test fun theEdgeShadowShowsOnlyWhileTheDrawerIsOnTheWay() {
        assertThat(drawerShadowFraction(0f)).isEqualTo(0f)
        assertThat(drawerShadowFraction(0.0005f)).isEqualTo(0f)
        assertThat(drawerShadowFraction(1f)).isEqualTo(0f)
        assertThat(drawerShadowFraction(0.02f)).isEqualTo(1f)
        assertThat(drawerShadowFraction(0.5f)).isEqualTo(1f)
    }

    @Test fun homeDimsAsTheDrawerRises() {
        assertThat(drawerScrimAlpha(0f)).isEqualTo(0f)
        assertThat(drawerScrimAlpha(0.5f)).isWithin(1e-6f).of(DRAWER_SCRIM_MAX_ALPHA / 2)
        assertThat(drawerScrimAlpha(1f)).isEqualTo(DRAWER_SCRIM_MAX_ALPHA)
        assertThat(DRAWER_SCRIM_MAX_ALPHA).isAtMost(0.4f)
    }

    /** A Telegram request: through a see-through drawer only the wallpaper should show, so home's icons
     *  and widgets fade out over the first stretch of the rise and are gone well before the drawer is open. */
    @Test fun homeContentFadesOutOverTheFirstStretchOfTheRise() {
        assertThat(homeContentAlpha(0f)).isEqualTo(1f)
        assertThat(homeContentAlpha(-0.1f)).isEqualTo(1f)
        assertThat(homeContentAlpha(HOME_FADE_SPAN / 2)).isWithin(1e-5f).of(0.5f)
        assertThat(homeContentAlpha(HOME_FADE_SPAN)).isEqualTo(0f)
        assertThat(homeContentAlpha(0.8f)).isEqualTo(0f)
        assertThat(homeContentAlpha(1f)).isEqualTo(0f)
        assertThat(homeContentAlpha(1.2f)).isEqualTo(0f)
        val samples = (0..20).map { homeContentAlpha(it / 20f) }
        assertThat(samples).isInOrder(Comparator.reverseOrder<Float>())
        assertThat(HOME_FADE_SPAN).isAtLeast(0.4f)
        assertThat(HOME_FADE_SPAN).isAtMost(0.8f)
    }
}
