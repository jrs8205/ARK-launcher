package org.arkikeskus.launcher.feature.home

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A Telegram request after 0.9.7: with "Widget edges align with icons" on, a widget keeps the same side
 * margin inside its cells as an app icon has inside its cell, instead of filling the span edge to edge
 * (what Launcher3's widget padding does). Off, nothing changes. Up and down the margin is the same but
 * never more than a quarter of the cell's height, so a one-row widget always keeps at least half its row
 * (A 120 x 60 cell with a 39 dp icon would otherwise leave no height at all).
 */
class WidgetInsetTest {
    @Test fun offTheWidgetFillsItsCells() {
        assertThat(widgetInsets(cellWidth = 90.dp, cellHeight = 100.dp, iconSize = 52.dp, alignWithIcons = false))
            .isEqualTo(WidgetInsets(0.dp, 0.dp))
    }

    @Test fun onTheSideInsetIsTheIconsSideMargin() {
        // A 90 dp cell with a 52 dp icon leaves 19 dp on each side of the icon; the cell is tall enough for the same up and down.
        assertThat(widgetInsets(cellWidth = 90.dp, cellHeight = 100.dp, iconSize = 52.dp, alignWithIcons = true))
            .isEqualTo(WidgetInsets(19.dp, 19.dp))
    }

    @Test fun aShortCellKeepsAtLeastHalfItsHeight() {
        // 120 x 60 with a 39 dp icon: 40.5 dp sideways, but only a quarter of the height (15 dp) up and down.
        assertThat(widgetInsets(cellWidth = 120.dp, cellHeight = 60.dp, iconSize = 39.dp, alignWithIcons = true))
            .isEqualTo(WidgetInsets(40.5.dp, 15.dp))
    }

    @Test fun anIconAsWideAsItsCellMeansNoInset() {
        assertThat(widgetInsets(cellWidth = 50.dp, cellHeight = 100.dp, iconSize = 52.dp, alignWithIcons = true))
            .isEqualTo(WidgetInsets(0.dp, 0.dp))
    }

    /** A placed widget gets only the inset its own minimum leaves room for, per axis and never a negative one. */
    @Test fun aPlacedWidgetKeepsItsMinimumSize() {
        val full = WidgetInsets(19.dp, 19.dp)
        // 180 x 200 dp span: no room sideways for a 180 dp minimum, 15 dp up and down for a 170 dp one.
        assertThat(insetsWithinMinimum(full, 180f, 200f, minWidthDp = 180f, minHeightDp = 170f)).isEqualTo(WidgetInsets(0.dp, 15.dp))
        assertThat(insetsWithinMinimum(full, 180f, 200f, minWidthDp = 100f, minHeightDp = 100f)).isEqualTo(full)
        assertThat(insetsWithinMinimum(full, 180f, 200f, minWidthDp = 250f, minHeightDp = 300f)).isEqualTo(WidgetInsets(0.dp, 0.dp))
    }

    /** The size the widget is told about shrinks by the inset on both sides, never below one dp. */
    @Test fun theReportedSizeLosesTheInsetOnBothSides() {
        assertThat(insetWidgetSizeDp(spanDp = 180, insetDp = 19)).isEqualTo(142)
        assertThat(insetWidgetSizeDp(spanDp = 30, insetDp = 19)).isEqualTo(1)
    }

    /** The cells a widget needs cover its size PLUS the inset on both sides, and the most it may
     *  take likewise: a 180 dp widget in 90 dp cells with a 19 dp inset needs three cells, not two. */
    @Test fun theCellsAWidgetNeedsCountTheInset() {
        assertThat(minimumWidgetCells(180f, 90f)).isEqualTo(2)
        assertThat(minimumWidgetCells(180f, 90f, insetDp = 19f)).isEqualTo(3)
        assertThat(maximumWidgetCells(250f, 90f, 5)).isEqualTo(2)
        assertThat(maximumWidgetCells(250f, 90f, 5, insetDp = 19f)).isEqualTo(3)
    }
}
