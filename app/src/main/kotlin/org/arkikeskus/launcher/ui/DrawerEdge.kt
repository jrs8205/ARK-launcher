package org.arkikeskus.launcher.ui

import androidx.compose.ui.unit.dp

/** Top-corner radius of the drawer while it moves: the corner size of Material 3 sheets. */
internal val DRAWER_CORNER_RADIUS = 28.dp

/** Shadow the drawer's top edge casts on home while it moves. */
internal val DRAWER_EDGE_ELEVATION = 8.dp

/** How dark home gets behind a fully open drawer (Material 3 scrim strength). */
internal const val DRAWER_SCRIM_MAX_ALPHA = 0.32f

/** The last stretch of the opening over which the corners square off. */
private const val SQUARE_OFF_SPAN = 0.15f

/** Below this the drawer counts as closed (the shell's own `drawerOpen` threshold). */
private const val CLOSED_PROGRESS = 0.001f

/** How round the drawer's top corners are at [progress] (0 closed, 1 open), as a share of
 *  [DRAWER_CORNER_RADIUS]: fully round while it moves, squaring off over the last stretch so the open
 *  drawer fills the screen edge to edge, as before. */
internal fun drawerCornerFraction(progress: Float): Float =
    ((1f - progress) / SQUARE_OFF_SPAN).coerceIn(0f, 1f)

/** Share of [DRAWER_EDGE_ELEVATION] at [progress]: none once open, and none while closed — the closed
 *  drawer is parked just below the screen, where a shadow would bleed a dark line onto the dock. */
internal fun drawerShadowFraction(progress: Float): Float =
    if (progress <= CLOSED_PROGRESS) 0f else drawerCornerFraction(progress)

/** How dark home is behind the drawer at [progress]: it dims as the drawer rises. */
internal fun drawerScrimAlpha(progress: Float): Float =
    DRAWER_SCRIM_MAX_ALPHA * progress.coerceIn(0f, 1f)

/** The first share of the rise over which home's icons and widgets fade out (a Telegram request: through
 *  a see-through drawer only the wallpaper should show, not the home screen under it). */
internal const val HOME_FADE_SPAN = 0.6f

/** How visible home's content is at [progress]: whole while the drawer is parked, gone from
 *  [HOME_FADE_SPAN] on, so an open drawer has only the wallpaper (blurred, if set) behind it. */
internal fun homeContentAlpha(progress: Float): Float =
    (1f - progress / HOME_FADE_SPAN).coerceIn(0f, 1f)
