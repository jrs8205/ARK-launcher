package org.arkikeskus.launcher.launcher.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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

    /** Bumped when READ_PHONE_STATE is granted while the status bar is already collecting (the
     *  onboarding flow): the callbackFlow below checks the permission once at collection start,
     *  so it must be restarted to register the permission-gated display callback. */
    private val permissionEpoch = MutableStateFlow(0)

    fun onPermissionsChanged() {
        permissionEpoch.value++
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val mobile: Flow<MobileStatus> = permissionEpoch.flatMapLatest {
        if (telephonyManager == null || !hasTelephony() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            flowOf(inactive)
        } else {
            callbackFlow {
                val executor = ContextCompat.getMainExecutor(context)
                var level = 0
                var display: TelephonyDisplayInfo? = null
                // Whether the cellular radio currently measures a real cell. Unlike data registration,
                // this does NOT drop when data hands over to Wi-Fi calling, so the indicator stays put
                // instead of flickering on/off as the phone moves data between cellular and Wi-Fi.
                var hasSignal = false
                fun send() {
                    // Hide the mobile indicator when there is no cellular service at all — no SIM,
                    // airplane mode, a SIM with no plan, or one fully parked on Wi-Fi with the radio
                    // powered down: every CellSignalStrength then reports UNAVAILABLE. Empty bars with
                    // no generation there look broken, so hide it like the no-SIM case.
                    if (!hasSignal) {
                        trySend(inactive)
                        return
                    }
                    trySend(MobileStatus(active = true, level = level, generation = generationOf(display)))
                }

                // Two SEPARATE callbacks: registerTelephonyCallback permission-checks the whole callback
                // object at registration, so bundling DisplayInfoListener (READ_PHONE_STATE on API 31/32)
                // with SignalStrengthsListener (no permission) made a denial throw and kill the level too
                // — no bars at all. Split, the bars always work and only the generation is permission-gated.
                val signalCallback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        level = signalStrength.level.coerceIn(0, 4)
                        // A real measurement on any cell type (dbm is a real negative value) means the
                        // radio is in cellular service; all-UNAVAILABLE means no service → hide.
                        hasSignal = signalStrength.cellSignalStrengths.any { it.dbm != CellInfo.UNAVAILABLE }
                        send()
                    }
                }
                val displayCallback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        display = telephonyDisplayInfo
                        send()
                    }
                }
                try {
                    telephonyManager.registerTelephonyCallback(executor, signalCallback)
                } catch (e: SecurityException) {
                    trySend(inactive)
                }
                val hasPhoneState = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPhoneState) {
                    try {
                        telephonyManager.registerTelephonyCallback(executor, displayCallback)
                    } catch (e: SecurityException) {
                        // Generation label stays null; the signal level still works.
                    }
                }
                awaitClose {
                    telephonyManager.unregisterTelephonyCallback(signalCallback)
                    if (hasPhoneState) telephonyManager.unregisterTelephonyCallback(displayCallback)
                }
            }
        }
            // Emit an immediate snapshot so combine() never stalls waiting for the first async signal
            // callback (it may be slow, or never arrive when READ_PHONE_STATE is denied). Without this a
            // single non-emitting flow would freeze the whole status bar on its default values. Kept
            // INSIDE flatMapLatest so every permission-epoch restart re-emits immediately too.
            .onStart { emit(inactive) }
            .distinctUntilChanged()
    }

    private fun hasTelephony(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /**
     * The label the system status bar would show: the carrier-override type takes priority (so NR-NSA
     * reads "5G" and aggregated LTE reads "4G"), falling back to the cellular network type the display
     * info reports ("LTE", "3G", "2G"). Requires READ_PHONE_STATE; without it the label is null and
     * only the level shows.
     *
     * The base type comes from [TelephonyDisplayInfo.getNetworkType] — the CELLULAR attachment — rather
     * than [TelephonyManager.getDataNetworkType]: when the phone routes data over Wi-Fi calling the data
     * type is IWLAN even though the SIM is camped on LTE/5G, which would drop the label. NOTE: this only
     * recovers the label on API 33+, where the platform derives getNetworkType() from the cellular
     * (WWAN PS) registration; on API 31/32 getNetworkType() is itself IWLAN in this state, so the label
     * stays absent there — no worse than before.
     */
    @SuppressLint("MissingPermission") // only reached from the display callback, which is
    // registered when READ_PHONE_STATE is granted; a revocation race lands in the catch below
    private fun generationOf(display: TelephonyDisplayInfo?): String? = try {
        when (display?.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED,
            -> "5G"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO,
            -> "4G"
            else -> when (display?.networkType ?: telephonyManager?.dataNetworkType) {
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
