package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkspacePagingTest {
    @Test
    fun emptyPagesOnEitherSideOfTheOriginalHomeRemainAccessible() {
        // A new page on the left, the original home at 1, and two empty pages on the right.
        val occupiedPages = setOf(1)
        for (page in 0..3) {
            assertThat(emptyPageReturnTarget(page, 4, page in occupiedPages)).isNull()
        }
    }

    @Test
    fun unusedTrailingPageReturnsToTheLastPermanentPageEvenWhenItIsEmpty() {
        // Permanent pages 2 and 3 have no icons; retreat from the extra drag page to 3, not 1.
        assertThat(emptyPageReturnTarget(4, 4, hasContent = false)).isEqualTo(3)
    }

    @Test
    fun aWorkspaceWithoutAnyItemsKeepsAllExplicitPages() {
        for (page in 0..2) {
            assertThat(emptyPageReturnTarget(page, 3, hasContent = false)).isNull()
        }
        assertThat(emptyPageReturnTarget(3, 3, hasContent = false)).isEqualTo(2)
    }

    @Test
    fun aDroppedItemKeepsItsPageBeforeThePermanentCountUpdates() {
        assertThat(emptyPageReturnTarget(2, 2, hasContent = true)).isNull()
    }

    @Test
    fun makingTheTrailingPagePermanentCancelsItsReturn() {
        assertThat(emptyPageReturnTarget(2, 2, hasContent = false)).isEqualTo(1)
        assertThat(emptyPageReturnTarget(2, 3, hasContent = false)).isNull()
    }

    @Test
    fun aSinglePageWorkspaceStillDiscardsOnlyItsUnusedDragPage() {
        assertThat(emptyPageReturnTarget(0, 1, hasContent = false)).isNull()
        assertThat(emptyPageReturnTarget(1, 1, hasContent = false)).isEqualTo(0)
    }
}
