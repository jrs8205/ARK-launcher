package org.arkikeskus.launcher.launcher.system

import android.app.Application
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.WifiBand
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.shadows.ShadowWifiManager

/**
 * On Android 11 the network capabilities carry no Wi-Fi details (WifiInfo became a TransportInfo in
 * 12), so the status bar showed every connected Wi-Fi at level 0 — red — with no band. There the
 * connection is read from the Wi-Fi manager instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class WifiStatusTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test fun androidElevenReadsTheConnectionFromTheWifiManager() {
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setRssi(-50)
        shadowOf(info).setFrequency(5180)
        shadowOf(context.getSystemService(WifiManager::class.java)).setConnectionInfo(info)
        ShadowWifiManager.setSignalLevelInPercent(0.9f)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

        val status = ConnectivityMonitor(context).wifiStatusOf(caps)

        assertThat(status.connected).isTrue()
        assertThat(status.level).isAtLeast(3)
        assertThat(status.band).isEqualTo(WifiBand.GHZ_5)
    }
}
