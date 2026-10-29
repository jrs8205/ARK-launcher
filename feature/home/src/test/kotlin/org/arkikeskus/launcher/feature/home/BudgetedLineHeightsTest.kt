package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The smartspace line budget: natural heights first, the leftover raising the shortest lines to one level. */
class BudgetedLineHeightsTest {
    @Test fun linesShorterThanTheirShareAllGrowToTheSameLevel() {
        assertThat(budgetedLineHeights(listOf(20, 22, 18), room = 120, target = 44)).containsExactly(40, 40, 40).inOrder()
    }

    @Test fun theLevelNeverExceedsTheTouchTarget() {
        assertThat(budgetedLineHeights(listOf(20, 20), room = 200, target = 44)).containsExactly(44, 44).inOrder()
    }

    @Test fun aTallLineKeepsItsNaturalHeightAndTheOthersShareTheRest() {
        // A two-line event (50) in a 120 px room with two short lines: 50 + 35 + 35.
        assertThat(budgetedLineHeights(listOf(20, 20, 50), room = 120, target = 44)).containsExactly(35, 35, 50).inOrder()
    }

    @Test fun aRoomSmallerThanTheContentLeavesEveryLineNatural() {
        assertThat(budgetedLineHeights(listOf(20, 30, 50), room = 60, target = 44)).containsExactly(20, 30, 50).inOrder()
        assertThat(budgetedLineHeights(listOf(20, 30), room = -10, target = 44)).containsExactly(20, 30).inOrder()
    }

    @Test fun noLinesIsNoBudget() {
        assertThat(budgetedLineHeights(emptyList(), room = 100, target = 44)).isEmpty()
    }
}
