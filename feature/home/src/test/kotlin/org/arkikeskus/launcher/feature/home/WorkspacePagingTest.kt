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

class ExplicitPageCountTest {
    @Test
    fun insertingInsideTheExplicitPagesGrowsTheFloorByOne() {
        assertThat(explicitPageCountAfterInsert(explicit = 3, at = 1)).isEqualTo(4)
        assertThat(explicitPageCountAfterInsert(explicit = 3, at = 3)).isEqualTo(4)
    }

    @Test
    fun insertingBeyondTheExplicitPagesMakesTheNewPageTheFloor() {
        // Content on pages 0..5, nothing explicit; a page added at 6 must persist while empty.
        assertThat(explicitPageCountAfterInsert(explicit = 0, at = 6)).isEqualTo(7)
        assertThat(explicitPageCountAfterInsert(explicit = 2, at = 4)).isEqualTo(5)
    }

    @Test
    fun removingAnExplicitPageShrinksTheFloorByOne() {
        assertThat(explicitPageCountAfterRemove(explicit = 3, page = 0)).isEqualTo(2)
        assertThat(explicitPageCountAfterRemove(explicit = 3, page = 2)).isEqualTo(2)
    }

    @Test
    fun removingAContentOnlyPageLeavesTheFloorAlone() {
        // Explicit 2, icons out to page 5: removing empty page 3 must not make pages 0..4 permanent.
        assertThat(explicitPageCountAfterRemove(explicit = 2, page = 3)).isEqualTo(2)
        assertThat(explicitPageCountAfterRemove(explicit = 0, page = 1)).isEqualTo(0)
    }
}

class PermanentPageCountTest {
    @Test
    fun countsFromTheStoredRowsAndTheExplicitFloor() {
        assertThat(permanentPageCount(emptySet(), 0)).isEqualTo(1)
        assertThat(permanentPageCount(setOf(0, 3), 0)).isEqualTo(4)
        assertThat(permanentPageCount(setOf(0), 6)).isEqualTo(6)
        assertThat(permanentPageCount(setOf(0, 99), 0)).isEqualTo(50)
    }
}
