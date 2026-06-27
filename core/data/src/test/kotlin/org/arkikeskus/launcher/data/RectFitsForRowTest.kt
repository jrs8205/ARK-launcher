package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.junit.Test

class RectFitsForRowTest {

    private fun fits(items: List<HomeItemEntity>, excludeRowId: Long, page: Int, x: Int, y: Int, sx: Int, sy: Int, cols: Int) =
        HomeLayoutRepository.rectFitsForRow(items, excludeRowId, page, x, y, sx, sy, cols)

    @Test
    fun `empty grid fits anywhere on-grid`() {
        assertThat(fits(emptyList(), -1L, page = 0, x = 1, y = 1, sx = 2, sy = 2, cols = 4)).isTrue()
    }

    @Test
    fun `off-grid is rejected`() {
        assertThat(fits(emptyList(), -1L, 0, -1, 0, 1, 1, 4)).isFalse()       // negative
        assertThat(fits(emptyList(), -1L, 0, 3, 0, 2, 1, 4)).isFalse()        // past right edge (3+2 > 4)
        assertThat(fits(emptyList(), -1L, 0, 0, 5, 1, 2, 4)).isFalse()        // past bottom (5+2 > 6 ROWS)
    }

    @Test
    fun `overlap with another item is rejected`() {
        val app = HomeItemEntity(page = 0, cellX = 1, cellY = 1)             // 1x1 app at (1,1)
        assertThat(fits(listOf(app), -1L, 0, 0, 0, 2, 2, 4)).isFalse()        // 2x2 at origin covers (1,1)
    }

    @Test
    fun `the excluded row does not block itself`() {
        val widget = HomeItemEntity(id = 7, page = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2, appWidgetId = 1, widgetProvider = "p")
        // Re-placing/resizing row 7 over its own cells is allowed (it is excluded from occupancy).
        assertThat(fits(listOf(widget), excludeRowId = 7, page = 0, x = 0, y = 0, sx = 3, sy = 2, cols = 4)).isTrue()
    }
}
