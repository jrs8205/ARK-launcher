package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.junit.Test

class ReflowPlanTest {

    private fun app(id: Long, spanX: Int = 1, spanY: Int = 1) = HomeItemEntity(
        id = id, packageName = "p$id", className = "C",
        page = 0, cellX = 0, cellY = 0, spanX = spanX, spanY = spanY,
    )

    private fun widget(id: Long, spanX: Int, spanY: Int) = HomeItemEntity(
        id = id, page = 0, cellX = 0, cellY = 0, spanX = spanX, spanY = spanY,
        appWidgetId = id.toInt(), widgetProvider = "com.w/P",
    )

    /** Every placement's full footprint must be unique cells — the invariant reflow exists to keep. */
    private fun assertNoOverlaps(plan: List<ReflowPlacement>) {
        val cells = HashSet<Triple<Int, Int, Int>>()
        for (p in plan) for (dx in 0 until p.spanX) for (dy in 0 until p.spanY) {
            assertThat(cells.add(Triple(p.page, p.cellX + dx, p.cellY + dy))).isTrue()
        }
    }

    @Test
    fun `packs 1x1 items sequentially in reading order`() {
        val plan = HomeLayoutRepository.reflowPlan(listOf(app(1), app(2), app(3)), columns = 2)
        assertThat(plan.map { Triple(it.page, it.cellX, it.cellY) })
            .containsExactly(Triple(0, 0, 0), Triple(0, 1, 0), Triple(0, 0, 1)).inOrder()
        assertNoOverlaps(plan)
    }

    @Test
    fun `reserves a widget's full footprint so items pack around it`() {
        val rows = listOf(widget(1, spanX = 4, spanY = 2), app(2), app(3), app(4))
        val plan = HomeLayoutRepository.reflowPlan(rows, columns = 5)
        val w = plan.first { it.id == 1L }
        assertThat(w.spanX).isEqualTo(4)
        assertThat(w.spanY).isEqualTo(2)
        for (p in plan.filter { it.id != 1L }) {
            val inside = p.page == w.page &&
                p.cellX in w.cellX until (w.cellX + w.spanX) &&
                p.cellY in w.cellY until (w.cellY + w.spanY)
            assertThat(inside).isFalse()
        }
        assertNoOverlaps(plan)
    }

    @Test
    fun `clamps a widget wider than the new grid`() {
        val plan = HomeLayoutRepository.reflowPlan(listOf(widget(1, spanX = 6, spanY = 2)), columns = 4)
        assertThat(plan.single().spanX).isEqualTo(4)
        assertNoOverlaps(plan)
    }

    @Test
    fun `spills overflow onto the next page`() {
        val rows = (1L..19L).map { app(it) } // columns=3, ROWS=6 -> 18 slots per page
        val plan = HomeLayoutRepository.reflowPlan(rows, columns = 3)
        assertThat(plan.count { it.page == 0 }).isEqualTo(18)
        assertThat(plan.last().page).isEqualTo(1)
        assertNoOverlaps(plan)
    }

    @Test
    fun `forces a corrupt non-widget span back to 1x1`() {
        val plan = HomeLayoutRepository.reflowPlan(listOf(app(1, spanX = 3, spanY = 2), app(2)), columns = 4)
        val first = plan.first { it.id == 1L }
        assertThat(first.spanX).isEqualTo(1)
        assertThat(first.spanY).isEqualTo(1)
        // The next item packs immediately beside it, not pushed out by the corrupt span.
        val second = plan.first { it.id == 2L }
        assertThat(Triple(second.page, second.cellX, second.cellY)).isEqualTo(Triple(0, 1, 0))
        assertNoOverlaps(plan)
    }
}
