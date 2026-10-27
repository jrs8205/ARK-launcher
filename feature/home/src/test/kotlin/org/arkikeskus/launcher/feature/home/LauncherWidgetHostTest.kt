package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A widget whose provider info was missing when the host views were built (its app was being
 * updated at that moment) stayed "Widget unavailable" until the widget set changed. The host now
 * reports the system's "providers changed" so the home screen can try again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherWidgetHostTest {
    private class Probe(context: Context) : LauncherWidgetHost(context, APPWIDGET_HOST_ID) {
        fun theSystemReportsProvidersChanged() = onProvidersChanged()
    }

    @Test fun aProviderChangeBumpsTheGeneration() {
        val host = Probe(RuntimeEnvironment.getApplication())
        assertThat(host.providerGeneration.value).isEqualTo(0)

        host.theSystemReportsProvidersChanged()
        host.theSystemReportsProvidersChanged()

        assertThat(host.providerGeneration.value).isEqualTo(2)
    }
}
