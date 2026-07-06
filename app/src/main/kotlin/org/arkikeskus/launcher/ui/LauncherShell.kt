package org.arkikeskus.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.feature.appdrawer.AppDrawerScreen
import org.arkikeskus.launcher.feature.home.HomeScreen
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.LocalIconPack
import org.arkikeskus.launcher.ui.component.LocalThemedIcons
import kotlin.math.roundToInt

/**
 * Hosts the launcher surface: the home screen with the app drawer as a **finger-following** slide-up
 * overlay. A swipe up on home drives [progress] (0 = closed, 1 = open) so the drawer tracks the
 * finger; releasing flings/settles to the nearest end. A pull-down at the top of the drawer (or BACK
 * / HOME) closes it. [onOpenSettings] is a long-press on home / the launcher's own drawer icon.
 *
 * This is also the launcher's drag layer: it owns the shared [HomeDragController] and draws the single
 * floating icon above every surface, so an icon can be dragged from the dock, the home grid, **or the
 * app drawer** onto any of them. Dragging an app out of the drawer slides the drawer closed (so home
 * is visible to drop onto) while keeping the drawer composed, so the in-flight gesture isn't cancelled.
 */
@Composable
fun LauncherShell(
    homeSignals: Flow<Boolean>,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val iconStyle by hiltViewModel<LauncherShellViewModel>().iconStyle.collectAsStateWithLifecycle()
    // Bounds keep a high-initial-velocity settle (the fling-carried spring below) from overshooting
    // past the ends — an overshoot past 1 would briefly translate the drawer above the screen top.
    val progress = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }
    var shellHeightPx by remember { mutableFloatStateOf(1f) }
    var shellOrigin by remember { mutableStateOf(Offset.Zero) }
    val settleSpring = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    // The shared drag state. Created here so both the home screen and the app drawer feed the same
    // controller and the floating icon below can travel between them.
    val dragController = rememberHomeDragController()
    var isDraggingDrawer by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }
    val currentProgress by remember {
        derivedStateOf { if (isDraggingDrawer) dragProgress else progress.value }
    }

    val drawerOpen by remember { derivedStateOf { currentProgress > 0.001f } }
    // While an app is being dragged out of the drawer, keep the drawer composed even after it has
    // slid shut — otherwise removing it from composition would cancel the in-progress drag gesture.
    // Keyed on [moving] (not [isDragging]): a still long-press only lifts (menu), and must NOT fade
    // the drawer — otherwise it flashes out and back when the menu opens.
    val draggingFromDrawer by remember {
        derivedStateOf { dragController.moving && dragController.source == DragSource.Drawer }
    }
    val drawerMounted by remember { derivedStateOf { drawerOpen || draggingFromDrawer } }
    // Smoothly cross-fade the drawer out as a drag-out begins (instead of a hard cut), then reset.
    val dragOutAlpha = remember { Animatable(1f) }
    LaunchedEffect(draggingFromDrawer) {
        if (draggingFromDrawer) dragOutAlpha.animateTo(0f, tween(durationMillis = 180))
        else dragOutAlpha.snapTo(1f)
    }

    // The single in-flight drawer-progress coroutine (a drag snapTo or a settle animateTo). Each new
    // drag/settle cancels the previous one, so a fast finger flick can't pile up competing snapTo/
    // animateTo jobs that make the drawer lag behind the finger.
    var dragJob by remember { mutableStateOf<Job?>(null) }

    fun animateProgress(target: Float) {
        isDraggingDrawer = false
        dragJob?.cancel()
        dragJob = scope.launch { progress.animateTo(target, settleSpring) }
    }

    fun dragDrawer(dyPx: Float) {
        if (!isDraggingDrawer) {
            isDraggingDrawer = true
            dragProgress = progress.value
        }
        // Swipe up (negative dy) increases progress; clamp to [0, 1].
        val target = (dragProgress - dyPx / shellHeightPx).coerceIn(0f, 1f)
        dragProgress = target
        dragJob?.cancel()
        dragJob = scope.launch { progress.snapTo(target) }
    }

    fun settleDrawer(velocityPxPerSec: Float) {
        val currentVal = if (isDraggingDrawer) dragProgress else progress.value
        isDraggingDrawer = false
        // Launcher3-style: a fling (release speed past the threshold) snaps to the direction of the
        // fling regardless of distance; otherwise settle by how far it was dragged. Velocity is px/s.
        val flingThreshold = 600f
        val target = when {
            velocityPxPerSec < -flingThreshold -> 1f // fast up → open
            velocityPxPerSec > flingThreshold -> 0f  // fast down → close
            else -> if (currentVal > 0.4f) 1f else 0f
        }
        dragJob?.cancel()
        dragJob = scope.launch {
            progress.snapTo(currentVal)
            // Carry the finger's release velocity into the spring (converted to progress units/s;
            // downward px velocity = negative progress velocity). Starting the spring from rest made a
            // fling-to-close visibly hesitate before accelerating — the "drawer lags coming down" feel.
            progress.animateTo(target, settleSpring, initialVelocity = -velocityPxPerSec / shellHeightPx)
        }
    }

    LaunchedEffect(homeSignals) {
        homeSignals.collect { animateProgress(0f) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { shellHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .onGloballyPositioned { shellOrigin = it.positionInRoot() },
    ) {
        HomeScreen(
            onOpenDrawer = { animateProgress(1f) },
            onDrawerDrag = { dragDrawer(it) },
            onDrawerSettle = { settleDrawer(it) },
            onOpenSettings = onOpenSettings,
            homeSignals = homeSignals,
            dragController = dragController,
            modifier = Modifier.fillMaxSize(),
        )

        // Always compose AppDrawerScreen at fillMaxSize to keep it pre-composed, pre-measured, and pre-laid out.
        // When closed, it is translated completely off-screen (translationY = size.height).
        // Compose's pointer input system respects the graphicsLayer transform, so it will not block pointer events for the HomeScreen.
        AppDrawerScreen(
            onClose = { animateProgress(0f) },
            onDrawerDrag = { dragDrawer(it) },
            onDrawerSettle = { settleDrawer(it) },
            onOpenSettings = {
                animateProgress(0f)
                onOpenSettings()
            },
            dragController = dragController,
            homeSignals = homeSignals,
            // "In use" rather than raw openness: a drag-out snaps progress to 0 mid-gesture, and the
            // scroll-to-top reset must NOT fire then — it could dispose the dragged item's node and kill
            // the in-flight gesture. It runs when the drag completes (this flips false), drawer hidden.
            drawerOpen = drawerOpen || draggingFromDrawer,
            // First movement of a drag-out reveals home/dock to drop onto. The drawer is hidden
            // with alpha rather than translated, so its (still-mounted) gesture keeps reporting
            // accurate local coordinates; progress is snapped shut so it's closed after the drop.
            onDragOutStart = {
                isDraggingDrawer = false
                scope.launch { progress.snapTo(0f) }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (draggingFromDrawer) {
                        alpha = dragOutAlpha.value
                    } else {
                        val ty = (1f - currentProgress) * size.height
                        translationY = ty
                    }
                },
        )

        // Single floating icon for any in-progress drag (home, dock or drawer), drawn above every
        // surface so it can travel between them. Positioned by the finger's root coords.
        val dragged = dragController.draggedApp
        if (dragged != null && dragController.moving) {
            val sizeDp = 56.dp
            val halfPx = with(density) { (sizeDp / 2).toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragController.rootPosition.x - shellOrigin.x - halfPx).roundToInt(),
                            (dragController.rootPosition.y - shellOrigin.y - halfPx).roundToInt(),
                        )
                    }
                    .size(sizeDp)
                    .graphicsLayer {
                        alpha = 0.92f
                        scaleX = 1.1f
                        scaleY = 1.1f
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Match the surfaces' icon style (the floating icon is a sibling of Home/Drawer, outside
                // their CompositionLocal scopes, so without this it reverts to the plain system icon).
                CompositionLocalProvider(
                    LocalIconPack provides iconStyle.iconPack,
                    LocalThemedIcons provides iconStyle.themed,
                ) {
                    AppIcon(
                        appItem = dragged,
                        labelColor = Color.White,
                        showLabel = false,
                        iconSize = 52.dp,
                    )
                }
            }
        }
    }

    BackHandler(enabled = drawerOpen) { animateProgress(0f) }
}
