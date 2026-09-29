package org.arkikeskus.launcher.feature.home

/**
 * Pure row-major packing for the people widget (JVM-testable): tiles keep their order, each takes
 * 1 or 2 columns, and whatever doesn't fit in [maxRows] rows folds into a "+N" chip that always
 * gets a cell of its own.
 */
internal object PeopleLayout {

    data class Placed<T>(val item: T, val row: Int, val col: Int, val span: Int)

    /** [placed] tiles plus the [overflow] count and the cell the chip goes in (meaningful when > 0). */
    data class Result<T>(val placed: List<Placed<T>>, val overflow: Int, val chipRow: Int, val chipCol: Int)

    fun <T> pack(items: List<T>, span: (T) -> Int, columns: Int, maxRows: Int): Result<T> {
        if (columns < 1 || maxRows < 1) return Result(emptyList(), items.size, 0, 0)
        val placed = ArrayList<Placed<T>>()
        var row = 0
        var col = 0
        var i = 0
        while (i < items.size) {
            val s = span(items[i]).coerceIn(1, columns)
            if (col + s > columns) {
                // Wrap only when there is a next row; otherwise the cursor stays on the last row so
                // the chip can still use its free tail.
                if (row + 1 >= maxRows) break
                row++
                col = 0
            }
            placed.add(Placed(items[i], row, col, s))
            col += s
            i++
        }
        val overflow = items.size - i
        if (overflow == 0) return Result(placed, 0, row, col)
        if (col < columns) return Result(placed, overflow, row, col)
        // The last row is full: the chip must still get a cell, so the last tile shrinks by one
        // column if it can (a squeezed tile keeps its avatar, name and count), else gives up its
        // cell. Either way the hidden count stays reachable.
        val last = placed.removeAt(placed.size - 1)
        return if (last.span > 1) {
            placed.add(last.copy(span = last.span - 1))
            Result(placed, overflow, last.row, last.col + last.span - 1)
        } else {
            Result(placed, overflow + 1, last.row, last.col)
        }
    }
}
