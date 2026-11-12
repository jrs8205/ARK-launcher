package org.arkikeskus.launcher.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The wallpaper colour the Chameleon follows: the last known one until Android answers, then what
 * Android says, and a change announced while the first answer was on its way is the newer of the two.
 */
class WallpaperArgbStateTest {
    private val sea = 0xFF1F8EF1.toInt()
    private val forest = 0xFF3A7D44.toInt()

    @Test fun untilTheFirstReadTheLastKnownColourStands() {
        assertThat(WallpaperArgbState(initial = sea).argb).isEqualTo(sea)
        assertThat(WallpaperArgbState(initial = null).argb).isNull()
    }

    @Test fun theFirstReadReplacesTheLastKnownColour() {
        val state = WallpaperArgbState(initial = sea)
        state.onFirstRead(Result.success(forest))
        assertThat(state.argb).isEqualTo(forest)
    }

    @Test fun aWallpaperThatReportsNoColourIsNoColour() {
        val state = WallpaperArgbState(initial = sea)
        state.onFirstRead(Result.success(null))
        assertThat(state.argb).isNull()
    }

    @Test fun aReadThatFailsKeepsTheLastKnownColour() {
        val state = WallpaperArgbState(initial = sea)
        state.onFirstRead(Result.failure(SecurityException("no access")))
        assertThat(state.argb).isEqualTo(sea)
    }

    @Test fun aChangeAnnouncedBeforeTheFirstReadAnswersWins() {
        val state = WallpaperArgbState(initial = null)
        state.onAnnounced(forest)
        state.onFirstRead(Result.success(sea))
        assertThat(state.argb).isEqualTo(forest)
    }

    @Test fun laterChangesAreFollowedIncludingToNoColour() {
        val state = WallpaperArgbState(initial = null)
        state.onFirstRead(Result.success(sea))
        state.onAnnounced(forest)
        assertThat(state.argb).isEqualTo(forest)
        state.onAnnounced(null)
        assertThat(state.argb).isNull()
    }
}
