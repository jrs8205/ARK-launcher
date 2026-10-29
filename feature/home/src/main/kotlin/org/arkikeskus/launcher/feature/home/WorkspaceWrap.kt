package org.arkikeskus.launcher.feature.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Infinite scrolling on the home pager. The pager always carries one trailing page after the last
 * real one (index == pageCount), the empty page an icon can be dragged onto. Outside a drag, with
 * infinite scrolling on, that tail shows a copy of page 0 instead: swiping on from the last page
 * slides page 0 in, and once the pager settles on the copy it swaps to the real page 0 in place.
 * The drag code never sees the copy — any drag turns the tail back into the empty new page.
 */

/**
 * Whether the trailing page shows a copy of page 0. Never during a drag, and not after one until
 * the pager has settled on a real page ([newPageLatched]), so a drop onto the new page never shows
 * page 0 before the dropped item lands. A single page has nothing to wrap to.
 */
internal fun tailShowsPageZero(
    infiniteScroll: Boolean,
    pageCount: Int,
    dragActive: Boolean,
    newPageLatched: Boolean,
): Boolean = infiniteScroll && pageCount >= 2 && !dragActive && !newPageLatched

/**
 * Pages to jump when a user drag runs past an end of the pager, or 0. Back from page 0 it jumps
 * forward onto the copy of page 0 (the pager can then keep scrolling back to the last page) —
 * unless the left-edge app owns that swipe. On past the copy it jumps back onto the real page 0.
 */
internal fun wrapJumpPages(
    atStart: Boolean,
    atEnd: Boolean,
    pageCount: Int,
    tailShowsPageZero: Boolean,
    leftEdgeAction: Boolean,
): Int = when {
    !tailShowsPageZero -> 0
    atStart && !leftEdgeAction -> pageCount
    atEnd -> -pageCount
    else -> 0
}

/** The dot to light for [pagerPage]: the copy of page 0 lights page 0's; the empty new page none. */
internal fun dotForPagerPage(pagerPage: Int, pageCount: Int, tailShowsPageZero: Boolean): Int =
    if (tailShowsPageZero && pagerPage >= pageCount) 0 else pagerPage

/** Whether any of the copy of page 0 is on screen, from the pager's continuous [position]. */
internal fun tailCopyOnScreen(position: Float, pageCount: Int): Boolean =
    position > pageCount - 1 + POSITION_EPSILON

/**
 * Whether this instance of [page] hosts its widgets' views. A view can sit in only one parent, and
 * page 0 can be composed twice (the real page and the copy at the tail, e.g. while one is only
 * prefetched), so its views follow whichever instance is on screen. Other pages exist once.
 */
internal fun ownsWidgetViews(page: Int, isTailCopy: Boolean, copyOnScreen: Boolean): Boolean =
    page != 0 || isTailCopy == copyOnScreen

/**
 * How far (in pages) a scroll from [before] to [after] ran past [lastPage] onto the empty drop page,
 * which a swipe must not reach: 0 when it stayed inside the pages, and 0 when the pager was already on
 * the drop page (a drag left it there; the workspace's retreat brings it back).
 */
internal fun dropPageOverrun(before: Float, after: Float, lastPage: Int): Float =
    if (before > lastPage + POSITION_EPSILON) 0f else (after - lastPage).coerceAtLeast(0f)

/**
 * The pager's overscroll: the platform effect (the edge stretch), plus the wrap-around. When a user
 * drag runs past an end it moves the pager — inside the same drag — by whole pages
 * ([wrapJumpPages]): from page 0 onto the copy at the tail, from the copy back onto page 0. Both
 * show the same content, so the jump is invisible and the rest of the drag scrolls on from there.
 * [PagerState.dispatchRawDelta] moves the pager without cancelling the drag that is scrolling it.
 * [onWrap] runs just before a jump (true = onto the copy), while the pager is still where it was.
 * [onStartOverscroll] runs for each pull back on page 0 that neither wraps nor belongs to the
 * left-edge app.
 *
 * Without the copy, the empty page after the last one is only for dropping icons (drags reach it with
 * animateScrollToPage, which never comes through here): a swipe or its fling that runs past the last
 * page is cut back to it ([dropPageOverrun]) and the rest goes to the stretch, exactly like at page 0.
 * [onEndOverscroll] runs for each such cut of a user drag.
 */
@Composable
internal fun rememberWrapAroundOverscroll(
    pagerState: PagerState,
    pageCount: Int,
    tailShowsPageZero: Boolean,
    leftEdgeAction: Boolean,
    onWrap: (toTailCopy: Boolean) -> Unit = {},
    onStartOverscroll: () -> Unit = {},
    onEndOverscroll: () -> Unit = {},
): OverscrollEffect {
    val platform = rememberOverscrollEffect()
    val config = rememberUpdatedState(
        WrapConfig(pageCount, tailShowsPageZero, leftEdgeAction, onWrap, onStartOverscroll, onEndOverscroll),
    )
    return remember(pagerState, platform) { WrapAroundOverscroll(pagerState, platform) { config.value } }
}

private class WrapConfig(
    val pageCount: Int,
    val tailShowsPageZero: Boolean,
    val leftEdgeAction: Boolean,
    val onWrap: (toTailCopy: Boolean) -> Unit,
    val onStartOverscroll: () -> Unit,
    val onEndOverscroll: () -> Unit,
)

private class WrapAroundOverscroll(
    private val pagerState: PagerState,
    private val platform: OverscrollEffect?,
    private val config: () -> WrapConfig,
) : OverscrollEffect {
    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val wrappingScroll: (Offset) -> Offset = { available ->
            val c = config()
            val before = position()
            var consumed = performScroll(available)
            if (!c.tailShowsPageZero) {
                val after = position()
                val overrun = dropPageOverrun(before, after, lastPage = c.pageCount - 1)
                val stride = pagerState.layoutInfo.pageSize + pagerState.layoutInfo.pageSpacing
                if (overrun > 0f && stride > 0) {
                    pagerState.dispatchRawDelta(-overrun * stride)
                    // What ran onto the drop page was not consumed after all: the stretch takes it.
                    consumed *= (after - before - overrun) / (after - before)
                    if (source == NestedScrollSource.UserInput) c.onEndOverscroll()
                }
            }
            val leftover = available - consumed
            if (source != NestedScrollSource.UserInput || leftover == Offset.Zero) {
                consumed
            } else {
                val atStart = !pagerState.canScrollBackward
                val pages = wrapJumpPages(
                    atStart = atStart,
                    atEnd = !pagerState.canScrollForward,
                    pageCount = c.pageCount,
                    tailShowsPageZero = c.tailShowsPageZero,
                    leftEdgeAction = c.leftEdgeAction,
                )
                val stride = pagerState.layoutInfo.pageSize + pagerState.layoutInfo.pageSpacing
                if (pages == 0 || stride <= 0) {
                    if (pages == 0 && atStart && !c.leftEdgeAction) c.onStartOverscroll()
                    consumed
                } else {
                    c.onWrap(pages > 0)
                    pagerState.dispatchRawDelta(pages * stride.toFloat())
                    consumed + performScroll(leftover)
                }
            }
        }
        return platform?.applyToScroll(delta, source, wrappingScroll) ?: wrappingScroll(delta)
    }

    private fun position(): Float = pagerState.currentPage + pagerState.currentPageOffsetFraction

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        if (platform != null) platform.applyToFling(velocity, performFling) else performFling(velocity)
    }

    override val isInProgress: Boolean
        get() = platform?.isInProgress ?: false

    override val node: DelegatableNode
        get() = platform?.node ?: super.node
}

/**
 * Accessibility paging (TalkBack, Switch Access) that does at an end what a swipe does there, while
 * [enabled] (the pager scrolls). The pager's own page actions know nothing of the wrap or the drop
 * page, and Compose offers the platform's scroll forward / backward, and turns them into those page
 * actions, only while the scroll position has room that way.
 * - Page 0 may wrap back ([wrapJumpPages]): the position reads one page further in, and paging back
 *   from page 0 jumps onto the copy of page 0 at the tail (the same content, so the jump is unseen),
 *   then slides back onto the last page. [onWrap] runs just before the jump, in the same scroll, so a
 *   jump that never happens (another scroll won) never announces one. Paging on needs nothing: the
 *   copy is a real pager page.
 * - Without the copy, the empty page past the last one is for drags only: the position ends at the
 *   last page, and paging on from it only calls [onEndReached] — as paging back from page 0 does,
 *   unless the left-edge app owns that end.
 */
internal fun Modifier.wrapAroundPagingSemantics(
    pageSpec: AnimationSpec<Float>,
    pagerState: PagerState,
    pageCount: Int,
    enabled: Boolean,
    tailShowsPageZero: Boolean,
    leftEdgeAction: Boolean,
    scope: CoroutineScope,
    onWrap: () -> Unit,
    onEndReached: () -> Unit,
): Modifier = when {
    !enabled -> this
    !tailShowsPageZero -> semantics {
        val lastPage = pageCount - 1
        horizontalScrollAxisRange = ScrollAxisRange(
            value = {
                val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                position.coerceAtMost(lastPage.toFloat()) * pagerState.stride()
            },
            maxValue = { lastPage * pagerState.stride() },
        )
        pageLeft {
            if (pagerState.pageBack(scope, pageSpec)) return@pageLeft true
            if (!leftEdgeAction) onEndReached()
            false
        }
        pageRight {
            val next = pagerState.currentPage + 1
            if (next > lastPage) {
                onEndReached()
                return@pageRight false
            }
            scope.launch { pagerState.animateScrollToPage(next, animationSpec = pageSpec) }
            true
        }
    }
    wrapJumpPages(atStart = true, atEnd = false, pageCount, tailShowsPageZero, leftEdgeAction) != 0 -> semantics {
        horizontalScrollAxisRange = ScrollAxisRange(
            value = { (pagerState.currentPage + pagerState.currentPageOffsetFraction + 1f) * pagerState.stride() },
            maxValue = { (pageCount + 1) * pagerState.stride() },
        )
        pageLeft {
            if (pagerState.pageBack(scope, pageSpec)) return@pageLeft true
            val stride = pagerState.stride()
            if (stride <= 0f) return@pageLeft false
            scope.launch {
                pagerState.scroll {
                    onWrap()
                    scrollBy(pageCount * stride)
                }
                pagerState.animateScrollToPage(pageCount - 1, animationSpec = pageSpec)
            }
            true
        }
        // Paging on needs no wrap logic (the copy is a real page), but the pager's own action
        // would run at its default speed: the user's speed applies here too.
        pageRight { pagerState.pageOn(scope, pageSpec) }
    }
    // The copy at the tail with the left-edge app owning page 0's back: nothing to wrap, but the
    // pager's own actions would ignore the user's speed.
    else -> semantics {
        pageLeft { pagerState.pageBack(scope, pageSpec) }
        pageRight { pagerState.pageOn(scope, pageSpec) }
    }
}

/** The pager's own page on at the user's speed: false at the pager's last page. */
private fun PagerState.pageOn(scope: CoroutineScope, pageSpec: AnimationSpec<Float>): Boolean {
    if (!canScrollForward) return false
    scope.launch { animateScrollToPage(currentPage + 1, animationSpec = pageSpec) }
    return true
}

/** The pager's own page back: false on page 0. */
private fun PagerState.pageBack(scope: CoroutineScope, pageSpec: AnimationSpec<Float>): Boolean {
    if (!canScrollBackward) return false
    scope.launch { animateScrollToPage(currentPage - 1, animationSpec = pageSpec) }
    return true
}

private fun PagerState.stride(): Float = (layoutInfo.pageSize + layoutInfo.pageSpacing).toFloat()

/**
 * Swaps the copy of page 0 at the tail for the real page 0 once the pager rests on it: same
 * content, no animation, so it looks like page 0 slid in. [onBeforeSwap] runs just before, while
 * the pager is still on the copy.
 */
@Composable
internal fun PageZeroCopySwap(
    pagerState: PagerState,
    pageCount: Int,
    tailShowsPageZero: Boolean,
    onBeforeSwap: () -> Unit = {},
) {
    val latestOnBeforeSwap by rememberUpdatedState(onBeforeSwap)
    LaunchedEffect(pagerState, pageCount, tailShowsPageZero) {
        if (!tailShowsPageZero) return@LaunchedEffect
        snapshotFlow {
            !pagerState.isScrollInProgress && pagerState.currentPage == pageCount &&
                abs(pagerState.currentPageOffsetFraction) < POSITION_EPSILON
        }.collect { restsOnCopy ->
            if (restsOnCopy) {
                latestOnBeforeSwap()
                pagerState.scrollToPage(0)
            }
        }
    }
}

private const val POSITION_EPSILON = 0.001f
