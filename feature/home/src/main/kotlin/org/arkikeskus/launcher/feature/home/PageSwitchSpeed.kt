package org.arkikeskus.launcher.feature.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.compositionLocalOf
import org.arkikeskus.launcher.model.LauncherSettings

/** The user's page-switch speed (Settings > Home, issue #18); the workspace reads it for its pager. */
val LocalPageSwitchSpeed = compositionLocalOf { LauncherSettings.SPEED_NORMAL }

/**
 * The spring the home pager settles with, for its snap after a swipe and for every programmatic
 * page change. Normal is Compose's own pager default; an unknown value (an older backup) is normal.
 */
internal fun pageSwitchSpec(speed: String): SpringSpec<Float> = spring(
    stiffness = when (speed) {
        LauncherSettings.SPEED_FAST -> Spring.StiffnessMedium
        LauncherSettings.SPEED_FASTEST -> Spring.StiffnessHigh
        else -> Spring.StiffnessMediumLow
    },
    visibilityThreshold = Int.VisibilityThreshold.toFloat(),
)
