package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PeopleLayoutTest {

    // Items are their own span: 1 = quiet tile, 2 = tile with content.
    private fun pack(items: List<Int>, columns: Int, rows: Int) = PeopleLayout.pack(items, { it }, columns, rows)

    @Test
    fun `fills rows left to right and wraps a wide tile that does not fit`() {
        val r = pack(listOf(1, 1, 1, 2, 1), columns = 4, rows = 3)
        assertThat(r.overflow).isEqualTo(0)
        assertThat(r.placed.map { Triple(it.row, it.col, it.span) }).containsExactly(
            Triple(0, 0, 1), Triple(0, 1, 1), Triple(0, 2, 1), Triple(1, 0, 2), Triple(1, 2, 1),
        ).inOrder()
    }

    @Test
    fun `overflow chip takes the free tail of the last row`() {
        val r = pack(listOf(2, 1, 2, 2), columns = 3, rows = 2)
        // Row 0: 2+1 → row 1: 2, then the last 2 doesn't fit in the remaining 1 and has no row.
        assertThat(r.placed).hasSize(3)
        assertThat(r.overflow).isEqualTo(1)
        assertThat(r.chipRow to r.chipCol).isEqualTo(1 to 2)
    }

    @Test
    fun `a full last row shrinks its wide tile to make room for the chip`() {
        val r = pack(listOf(2, 1, 2, 2, 1, 1), columns = 4, rows = 2)
        // Row 0: 2+1 → row 1: 2+2 fills it; two items left. The last 2 becomes a 1, the chip takes its cell.
        assertThat(r.placed).hasSize(4)
        assertThat(r.placed.last().span).isEqualTo(1)
        assertThat(r.overflow).isEqualTo(2)
        assertThat(r.chipRow to r.chipCol).isEqualTo(1 to 3)
    }

    @Test
    fun `overflow chip evicts the last tile when the grid is full`() {
        val r = pack(listOf(1, 1, 1, 1, 1), columns = 2, rows = 2)
        assertThat(r.placed).hasSize(3)
        assertThat(r.overflow).isEqualTo(2)
        assertThat(r.chipRow to r.chipCol).isEqualTo(1 to 1)
    }

    @Test
    fun `overflow chip shrinks a wide last tile instead of evicting it`() {
        val r = pack(listOf(2, 2), columns = 2, rows = 1)
        assertThat(r.placed).hasSize(1)
        assertThat(r.placed[0].span).isEqualTo(1)
        assertThat(r.overflow).isEqualTo(1)
        assertThat(r.chipRow to r.chipCol).isEqualTo(0 to 1)
    }

    @Test
    fun `a span wider than the grid is clamped`() {
        val r = pack(listOf(2, 2), columns = 1, rows = 2)
        assertThat(r.placed.map { it.span }).containsExactly(1, 1)
        assertThat(r.overflow).isEqualTo(0)
    }

    @Test
    fun `degenerate grid places nothing`() {
        assertThat(pack(listOf(1, 1), columns = 0, rows = 3).overflow).isEqualTo(2)
    }
}
