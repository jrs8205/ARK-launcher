package org.arkikeskus.launcher.feature.home

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A Telegram request after 0.9.7: with "Widget edges align with icons" on, a widget keeps the same side
 * margin inside its cells as an app icon has inside its cell, instead of filling the span edge to edge
 * (what Launcher3's widget padding does). Off, nothing changes.
 */
class WidgetInsetTest {
    @Test fun offTheWidgetFillsItsCells() {
        assertThat(widgetInset(cellWidth = 90.dp, iconSize = 52.dp, alignWithIcons = false)).isEqualTo(0.dp)
    }

    @Test fun onTheInsetIsTheIconsSideMargin() {
        // A 90 dp cell with a 52 dp icon leaves 19 dp on each side of the icon.
        assertThat(widgetInset(cellWidth = 90.dp, iconSize = 52.dp, alignWithIcons = true)).isEqualTo(19.dp)
    }

    @Test fun anIconAsWideAsItsCellMeansNoInset() {
        assertThat(widgetInset(cellWidth = 50.dp, iconSize = 52.dp, alignWithIcons = true)).isEqualTo(0.dp)
    }

    /** The size the widget is told about shrinks by the inset on both sides, never below one dp. */
    @Test fun theReportedSizeLosesTheInsetOnBothSides() {
        assertThat(insetWidgetSizeDp(spanDp = 180, insetDp = 19)).isEqualTo(142)
        assertThat(insetWidgetSizeDp(spanDp = 30, insetDp = 19)).isEqualTo(1)
    }
}
