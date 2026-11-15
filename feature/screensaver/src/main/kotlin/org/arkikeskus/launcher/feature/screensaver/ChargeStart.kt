package org.arkikeskus.launcher.feature.screensaver

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.screensaverPreferences
import javax.inject.Inject

/**
 * Brings the screensaver up as soon as charging begins on a phone whose screen is off, when
 * the user asked for that (see [chargeStartAction] for why Android would otherwise wait).
 */
class ChargeStart @Inject constructor(@ApplicationContext private val context: Context) {

    private val ourDream: String = ComponentName(context, ScreensaverService::class.java).flattenToString()

    fun onPowerConnected() {
        val prefs = context.screensaverPreferences()
        if (!prefs.getBoolean(DreamPreferences.KEY_START_ON_CHARGE, false)) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        val action = chargeStartAction(
            enabled = true,
            // Interactive also covers a screensaver Android is just starting by itself.
            screenOn = power.isInteractive,
            dreamShowing = ScreensaverService.isDreaming,
            selection = readSelection(),
        )
        if (action == ChargeStartAction.WAKE_SCREEN) wakeScreen(power)
    }

    /** Uncertain when Android does not let an ordinary app read these settings. */
    private fun readSelection(): ChargeDream = try {
        val resolver = context.contentResolver
        chargeDreamSelection(
            Settings.Secure.getString(resolver, SCREENSAVER_ENABLED),
            Settings.Secure.getString(resolver, SCREENSAVER_ACTIVATE_ON_SLEEP),
            Settings.Secure.getString(resolver, SCREENSAVER_COMPONENTS),
            ourDream,
        )
    } catch (_: SecurityException) {
        ChargeDream.UNKNOWN
    }

    /**
     * A screen-on wake lock that turns the screen on is the one wake an app has. Held a moment
     * only: the lock screen's own short timeout then runs into the screensaver. Tried on
     * Android 17 with the launcher's own target (2026-11-15): the screen wakes.
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen(power: PowerManager) {
        power.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            WAKE_LOCK_TAG,
        ).acquire(WAKE_HOLD_MILLIS)
    }

    private companion object {
        const val SCREENSAVER_ENABLED = "screensaver_enabled"
        const val SCREENSAVER_ACTIVATE_ON_SLEEP = "screensaver_activate_on_sleep"
        const val SCREENSAVER_COMPONENTS = "screensaver_components"

        const val WAKE_LOCK_TAG = "ArkLauncher:chargeStart"
        const val WAKE_HOLD_MILLIS = 1_000L
    }
}
