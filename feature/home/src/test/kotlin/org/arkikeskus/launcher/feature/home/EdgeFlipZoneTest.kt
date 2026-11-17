package org.arkikeskus.launcher.feature.home

import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How near an edge of the grid a dragged item must be held for the page to flip (issue #30). */
class EdgeFlipZoneTest {
    private val density = Density(2f)

    @Test
    fun `on an ordinary grid the zone is 32 dp`() {
        // Four columns on a 400 dp screen: a column is 100 dp, 200 px here.
        assertThat(edgeFlipZonePx(cellWidthPx = 200f, density = density)).isEqualTo(64f)
    }

    @Test
    fun `on a dense grid the zone stops short of the outermost column's middle`() {
        // Seven columns on a 360 dp screen: a column is about 51 dp, its middle 25.7 dp from the edge.
        val cell = 360f / 7f * density.density
        val zone = edgeFlipZonePx(cellWidthPx = cell, density = density)

        assertThat(zone).isLessThan(cell / 2f)
        assertThat(zone).isWithin(0.01f).of(cell * 0.4f)
    }

    @Test
    fun `before the grid is measured there is no zone`() {
        assertThat(edgeFlipZonePx(cellWidthPx = 0f, density = density)).isEqualTo(0f)
    }
}
