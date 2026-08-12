package org.arkikeskus.launcher.feature.home

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Double-tap-to-lock. The ONLY thing this service ever does is
 * `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` when the home screen detects a double-tap on
 * empty space — locking the screen simply isn't possible for a normal app any other way (short of
 * a device-admin policy that breaks fingerprint unlock). It reads no accessibility events and no
 * screen content: see res/xml/lock_accessibility_service.xml (no event types, no window content).
 */
class LockAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: LockAccessibilityService? = null

        /** Locks the screen; false when the service isn't enabled (or not yet connected). */
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        /** Whether the user has allowed the service in the system accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val target = ComponentName(context, LockAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
        }
    }
}
