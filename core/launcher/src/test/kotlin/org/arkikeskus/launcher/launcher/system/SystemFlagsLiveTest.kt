package org.arkikeskus.launcher.launcher.system

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.SystemFlags
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * The status bar's VPN key and Data Saver glyph follow their own changes. They were read only when
 * some other flag changed, so a VPN or Data Saver toggled from the shade over the home screen did
 * not show (or go away) until the ringer, an alarm or Bluetooth happened to change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SystemFlagsLiveTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))

    @Test fun aVpnComingUpIsReportedByItself() = runTest {
        val seen = mutableListOf<SystemFlags>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { SystemFlagsMonitor(context).flags.collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(seen.last().vpn).isFalse()

        val vpn = ShadowNetwork.newInstance(42)
        connectivity.addNetwork(
            vpn,
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_VPN, 0, true, NetworkInfo.State.CONNECTED),
        )
        connectivity.setNetworkCapabilities(
            vpn,
            ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN) },
        )
        // The system tells the monitor's own callback; nothing else changes.
        connectivity.networkCallbacks.forEach { it.onAvailable(vpn) }
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(seen.last().vpn).isTrue()
    }

    @Test fun dataSaverSwitchedOnIsReportedByItself() = runTest {
        val seen = mutableListOf<SystemFlags>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { SystemFlagsMonitor(context).flags.collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(seen.last().dataSaver).isFalse()

        connectivity.setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        context.sendBroadcast(Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(seen.last().dataSaver).isTrue()
    }
}
