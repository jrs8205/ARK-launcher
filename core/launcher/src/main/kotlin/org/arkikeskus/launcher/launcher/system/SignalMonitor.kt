package org.arkikeskus.launcher.launcher.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import org.arkikeskus.launcher.model.MobileStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams the mobile signal level (0..4) and data generation (5G/4G/3G). The generation requires
 * READ_PHONE_STATE; without it the level still shows and the generation is null.
 */
@Singleton
class SignalMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)

    private val inactive = MobileStatus(active = false, level = 0, generation = null)

    val mobile: Flow<MobileStatus> =
        if (telephonyManager == null || !hasTelephony() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            flowOf(inactive)
        } else {
            callbackFlow {
                val executor = ContextCompat.getMainExecutor(context)
                var level = 0
                var display: TelephonyDisplayInfo? = null
                val callback = object :
                    TelephonyCallback(),
                    TelephonyCallback.SignalStrengthsListener,
                    TelephonyCallback.DisplayInfoListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        level = signalStrength.level.coerceIn(0, 4)
                        trySend(MobileStatus(active = true, level = level, generation = generationOf(display)))
                    }

                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        display = telephonyDisplayInfo
                        trySend(MobileStatus(active = true, level = level, generation = generationOf(display)))
                    }
                }
                try {
                    telephonyManager.registerTelephonyCallback(executor, callback)
                } catch (e: SecurityException) {
                    trySend(inactive)
                }
                awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
            }
        }
            // Emit an immediate snapshot so combine() never stalls waiting for the first async signal
            // callback (it may be slow, or never arrive when READ_PHONE_STATE is denied). Without this a
            // single non-emitting flow would freeze the whole status bar on its default values.
            .onStart { emit(inactive) }
            .distinctUntilChanged()

    private fun hasTelephony(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /**
     * The label the system status bar would show: the carrier-override type takes priority (so NR-NSA
     * reads "5G" and aggregated LTE reads "4G"), falling back to the base data network type ("LTE",
     * "3G", "2G"). Requires READ_PHONE_STATE; without it the label is null and only the level shows.
     */
    private fun generationOf(display: TelephonyDisplayInfo?): String? = try {
        when (display?.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED,
            -> "5G"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO,
            -> "4G"
            else -> when (telephonyManager?.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                -> "3G"
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                -> "2G"
                else -> null
            }
        }
    } catch (e: SecurityException) {
        null
    }
}
