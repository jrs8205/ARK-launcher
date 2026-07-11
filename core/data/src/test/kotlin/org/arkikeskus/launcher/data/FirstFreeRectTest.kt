package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.junit.Test

class FirstFreeRectTest {

    private fun rect(items: List<HomeItemEntity>, columns: Int, sx: Int, sy: Int) =
        HomeLayoutRepository.firstFreeRect(items, columns, sx, sy)

    @Test
    fun `empty grid places at origin`() {
        assertThat(rect(emptyList(), columns = 4, sx = 2, sy = 2)).isEqualTo(Triple(0, 0, 0))
    }

    @Test
    fun `a 2x2 widget at origin pushes a 1x1 to the right of it`() {
        val w = HomeItemEntity(page = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2, appWidgetId = 1, widgetProvider = "p")
        assertThat(rect(listOf(w), columns = 4, sx = 1, sy = 1)).isEqualTo(Triple(0, 2, 0))
    }

    @Test
    fun `oversized span is clamped to the grid and still places`() {
        assertThat(rect(emptyList(), columns = 4, sx = 10, sy = 10)).isEqualTo(Triple(0, 0, 0))
    }

    @Test
    fun `a full page advances placement to the next page`() {
        // columns=2, ROWS=6 → a 2x6 widget fills page 0 entirely.
        val full = HomeItemEntity(page = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 6, appWidgetId = 1, widgetProvider = "p")
        assertThat(rect(listOf(full), columns = 2, sx = 1, sy = 1)).isEqualTo(Triple(1, 0, 0))
    }

    // --- firstRectWithPush: the picker-add path must push icons aside before paging over ---------

    private fun icon(id: Long, cellY: Int, cellX: Int = 0) = HomeItemEntity(
        id = id, packageName = "p$id", className = "C", page = 0, cellX = cellX, cellY = cellY,
    )

    @Test
    fun `scattered icons are pushed aside instead of exiling a full-width widget to a new page`() {
        // One icon on rows 1, 3 and 5 makes every row-pair "occupied" for a full-width 5x2 rect,
        // yet the page is nearly empty — the add must relocate the icons, not vanish to page 1.
        val items = listOf(icon(1, cellY = 1), icon(2, cellY = 3), icon(3, cellY = 5))
        val p = HomeLayoutRepository.firstRectWithPush(items, columns = 5, spanX = 5, spanY = 2, rows = 6)
        assertThat(p.page).isEqualTo(0)
        assertThat(p.moved).isNotEmpty()
        // The displaced icons land outside the widget's rectangle.
        val widgetCells = (p.cellX until p.cellX + 5).flatMap { x -> (p.cellY until p.cellY + 2).map { y -> x to y } }
        p.moved.values.forEach { assertThat(it in widgetCells).isFalse() }
    }

    @Test
    fun `a genuinely free rect needs no pushes`() {
        val items = listOf(icon(1, cellY = 5))
        val p = HomeLayoutRepository.firstRectWithPush(items, columns = 5, spanX = 5, spanY = 2, rows = 6)
        assertThat(p.page).isEqualTo(0)
        assertThat(p.moved).isEmpty()
    }

    @Test
    fun `a truly full page still falls back to a fresh page`() {
        val full = HomeItemEntity(id = 1, page = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 6, appWidgetId = 1, widgetProvider = "p")
        val p = HomeLayoutRepository.firstRectWithPush(listOf(full), columns = 2, spanX = 2, spanY = 2, rows = 6)
        assertThat(p.page).isEqualTo(1)
        assertThat(p.moved).isEmpty()
    }
}
