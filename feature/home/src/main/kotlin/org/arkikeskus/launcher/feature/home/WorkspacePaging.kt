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
