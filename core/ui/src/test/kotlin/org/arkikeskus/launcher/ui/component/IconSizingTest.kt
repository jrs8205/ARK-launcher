package org.arkikeskus.launcher.ui.component

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IconSizingTest {

    @Test
    fun a_roomy_cell_keeps_the_preferred_size() {
        assertThat(iconSizeForCell(80.dp, 52.dp)).isEqualTo(52.dp)
        assertThat(iconSizeForCell(80.dp, 52.dp, cellHeight = 90.dp, labelBlock = 17.dp)).isEqualTo(52.dp)
    }

    @Test
    fun a_narrow_cell_shrinks_the_icon_by_width() {
        assertThat(iconSizeForCell(48.dp, 52.dp)).isEqualTo(40.dp)
    }

    @Test
    fun a_short_cell_leaves_room_for_a_whole_label_line() {
        // 8 rows on the largest display size: a 52dp icon left the label 4dp of a 60dp cell.
        val icon = iconSizeForCell(80.dp, 52.dp, cellHeight = 60.dp, labelBlock = 17.dp)
        assertThat(icon).isEqualTo(39.dp)
        assertThat(icon + 17.dp).isAtMost(60.dp)
    }

    @Test
    fun a_narrow_cell_never_shrinks_the_icon_below_the_width_floor() {
        assertThat(iconSizeForCell(30.dp, 52.dp)).isEqualTo(32.dp)
    }

    @Test
    fun the_floor_yields_to_a_short_cell_so_the_largest_label_still_fits() {
        // The largest settings the launcher allows: label slider 160 %, system font capped at 130 %,
        // a 60dp cell (8 rows, enlarged display). A rigid 32dp floor needed 63dp and cut the label.
        val label = labelBlockHeight(showLabel = true, labelScale = 1.6f, fontFactor = MAX_LABEL_FONT_SCALE)
        val icon = iconSizeForCell(80.dp, 52.dp, cellHeight = 60.dp, labelBlock = label)
        assertThat(icon + label).isAtMost(60.dp)
        assertThat(icon).isAtLeast(24.dp)
    }

    @Test
    fun the_icon_never_shrinks_below_the_recognisable_minimum() {
        assertThat(iconSizeForCell(80.dp, 52.dp, cellHeight = 40.dp, labelBlock = 17.dp)).isEqualTo(24.dp)
    }

    @Test
    fun a_hidden_label_takes_no_room() {
        assertThat(labelBlockHeight(showLabel = false, labelScale = 1f, fontFactor = 1f)).isEqualTo(0.dp)
    }

    @Test
    fun the_label_block_is_the_gap_plus_the_scaled_line() {
        assertThat(labelBlockHeight(showLabel = true, labelScale = 1f, fontFactor = 1f)).isEqualTo(17.dp)
        assertThat(labelBlockHeight(showLabel = true, labelScale = 2f, fontFactor = 1f)).isEqualTo(30.dp)
        assertThat(labelBlockHeight(showLabel = true, labelScale = 1f, fontFactor = 1f, lines = 2)).isEqualTo(30.dp)
    }

    @Test
    fun the_system_font_scale_is_honoured_only_up_to_the_cap() {
        assertThat(labelFontFactor(0.85f)).isEqualTo(0.85f)
        assertThat(labelFontFactor(1f)).isEqualTo(1f)
        assertThat(labelFontFactor(2f)).isEqualTo(MAX_LABEL_FONT_SCALE)
    }
}
