package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WidgetSizingTest {
    @Test fun oldUndersizedWidgetCanBeResizedWithoutAnInvalidClampRange() {
        assertThat(resizeWidgetStartEdge(0, 2, -1, 3, 5)).isNull()
        assertThat(resizeWidgetStartEdge(1, 2, -1, 3, 5)).isEqualTo(0 to 3)
        assertThat(resizeWidgetStartEdge(0, 4, 1, 2, 5)).isEqualTo(1 to 3)
    }

    @Test fun minimumUsesActualCellHeightAndRoundsUp() {
        assertThat(minimumWidgetCells(130f, 60f)).isEqualTo(3)
        assertThat(minimumWidgetCells(130f, 100f)).isEqualTo(2)
        assertThat(minimumWidgetCells(120f, 60f)).isEqualTo(2)
    }

    @Test fun maximumNeverRoundsPastProviderLimit() {
        assertThat(maximumWidgetCells(179f, 60f, 8)).isEqualTo(2)
        assertThat(maximumWidgetCells(600f, 60f, 5)).isEqualTo(5)
        assertThat(maximumWidgetCells(0f, 60f, 8)).isEqualTo(8)
    }

    @Test fun dropPreservesGrabPointAndNewPage() {
        val p = widgetDropPlacement(2, 250f, 350f, 500f, 600f, 5, 6, 2, 2, 0.25f, 0.75f)
        assertThat(p?.page).isEqualTo(2)
        assertThat(p?.cellX).isEqualTo(2)
        assertThat(p?.cellY).isEqualTo(2)
    }

    @Test fun clampFootprintAtEdges() {
        val p = widgetDropPlacement(0, 499f, 599f, 500f, 600f, 5, 6, 3, 2, 0.5f, 0.5f)
        assertThat(p?.cellX).isEqualTo(2)
        assertThat(p?.cellY).isEqualTo(4)
    }

    @Test fun rejectDockOutsideGridAndOversizedWidget() {
        assertThat(widgetDropPlacement(0, 50f, 601f, 500f, 600f, 5, 6, 2, 2, .5f, .5f)).isNull()
        assertThat(widgetDropPlacement(0, -1f, 50f, 500f, 600f, 5, 6, 2, 2, .5f, .5f)).isNull()
        assertThat(widgetDropPlacement(0, 50f, 50f, 500f, 600f, 5, 6, 6, 2, .5f, .5f)).isNull()
    }
}
