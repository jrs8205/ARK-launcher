package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WidgetSizeReporterTest {
    private val reporter = WidgetSizeReporter()
    private val sent = mutableListOf<Triple<Int, Int, Int>>()

    private fun report(id: Int, w: Int, h: Int) = reporter.report(id, w, h) { sent += Triple(id, w, h) }

    @Test fun anUnchangedSizeIsReportedOnlyOnce() {
        repeat(60) { report(7, 395, 682) }

        assertThat(sent).containsExactly(Triple(7, 395, 682))
    }

    @Test fun aChangedSizeIsReportedAgain() {
        report(7, 395, 216)
        report(7, 395, 682)
        report(7, 395, 682)

        assertThat(sent).containsExactly(Triple(7, 395, 216), Triple(7, 395, 682)).inOrder()
    }

    @Test fun eachWidgetHasItsOwnLastSize() {
        report(7, 395, 216)
        report(8, 395, 216)

        assertThat(sent).hasSize(2)
    }

    @Test fun aFailedReportIsRetried() {
        reporter.report(7, 395, 216) { error("provider gone") }
        report(7, 395, 216)

        assertThat(sent).containsExactly(Triple(7, 395, 216))
    }

    @Test fun aForgottenWidgetIsReportedAgain() {
        report(7, 395, 216)
        reporter.forget(7)
        report(7, 395, 216)

        assertThat(sent).hasSize(2)
    }
}
