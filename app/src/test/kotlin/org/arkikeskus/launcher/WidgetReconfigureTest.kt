package org.arkikeskus.launcher

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A placed widget's "reconfigure" used a raw ACTION_APPWIDGET_CONFIGURE intent, which a provider
 * whose configuration activity is not exported refuses (nothing opened). Like the first
 * configuration, it now goes through the host, which starts the activity on the provider's behalf,
 * with a request code of its own: a cancelled reconfigure must never reach the pending-bind result
 * handling, which would delete a pending bind's id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetReconfigureTest {
    @Test fun theReconfigureGoesThroughTheHostWithItsOwnRequestCode() {
        val requests = mutableListOf<Pair<Int, Int>>()

        val started = startWidgetReconfigure(7) { appWidgetId, requestCode, _ -> requests += appWidgetId to requestCode }

        assertThat(started).isTrue()

        val (id, code) = requests.single()
        assertThat(id).isEqualTo(7)
        assertThat(code).isNotEqualTo(WIDGET_CONFIG_REQUEST)
        assertThat(isWidgetConfigResult(code)).isFalse()
        assertThat(isWidgetConfigResult(WIDGET_CONFIG_REQUEST)).isTrue()
    }

    @Test fun aHostThatRefusesReportsFalseSoTheCallerCanFallBack() {
        val started = startWidgetReconfigure(7) { _, _, _ -> throw SecurityException("not exported") }

        assertThat(started).isFalse()
    }
}
