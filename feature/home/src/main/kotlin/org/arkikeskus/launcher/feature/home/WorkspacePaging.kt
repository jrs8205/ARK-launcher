package org.arkikeskus.launcher.feature.home

import org.arkikeskus.launcher.data.HomeLayoutRepository

/**
 * How many pages the workspace has: as many as hold a stored row (min 1), or as many as the user
 * added explicitly, whichever is more, capped so a corrupt page value can never reach the dots.
 * Counted from the stored rows, not the resolved entries, so the UI state and the page menu's
 * operations (which read storage under their mutex) can never disagree about a page.
 */
internal fun permanentPageCount(occupiedPages: Set<Int>, explicit: Int): Int =
    maxOf((occupiedPages.maxOrNull() ?: 0) + 1, explicit).coerceIn(1, HomeLayoutRepository.MAX_PAGES)

/**
 * A null result keeps the settled page. Only an empty temporary page beyond the permanent pages
 * returns to the last permanent page; explicitly added empty pages remain usable.
 * Content can arrive from a drop before the permanent page count catches up.
 */
internal fun emptyPageReturnTarget(
    settledPage: Int,
    permanentPageCount: Int,
    hasContent: Boolean,
): Int? {
    if (hasContent || settledPage < permanentPageCount) return null
    return (permanentPageCount - 1).coerceAtLeast(0)
}

/**
 * The explicit page count (the floor the page menu maintains) after an empty page is opened at
 * [at]. Inside the floor it grows by one; beyond it, the new page becomes the floor so it persists
 * while empty. Even an entirely empty workspace has one implicit page to preserve.
 * Pages between the old floor and [at] hold content and stay as they are.
 */
internal fun explicitPageCountAfterInsert(explicit: Int, at: Int): Int = maxOf(explicit, 1, at) + 1

/**
 * The explicit page count after page [page] is removed: one less when the page was inside the
 * floor, unchanged when it lay beyond it (content-only pages must not become permanent).
 */
internal fun explicitPageCountAfterRemove(explicit: Int, page: Int): Int =
    if (page < explicit) explicit - 1 else explicit
