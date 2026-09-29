package org.arkikeskus.launcher.feature.home

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
 * while empty. Pages between the old floor and [at] hold content and stay as they are.
 */
internal fun explicitPageCountAfterInsert(explicit: Int, at: Int): Int = maxOf(explicit, at) + 1

/**
 * The explicit page count after page [page] is removed: one less when the page was inside the
 * floor, unchanged when it lay beyond it (content-only pages must not become permanent).
 */
internal fun explicitPageCountAfterRemove(explicit: Int, page: Int): Int =
    if (page < explicit) explicit - 1 else explicit
