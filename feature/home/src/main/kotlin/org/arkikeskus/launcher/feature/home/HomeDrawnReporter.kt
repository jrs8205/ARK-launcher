package org.arkikeskus.launcher.feature.home

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable

/**
 * Reports the home screen fully drawn to the system once its rows have [loaded] (the first frame
 * before that is the wallpaper and an empty grid). The startup benchmarks read the moment as the time
 * to full display, and the system's startup accounting (and its profile of the start) stop there.
 */
@Composable
internal fun HomeDrawnReporter(loaded: Boolean) {
    ReportDrawnWhen { loaded }
}
