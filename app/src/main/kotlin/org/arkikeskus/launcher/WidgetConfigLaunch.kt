package org.arkikeskus.launcher

import android.app.ActivityOptions
import android.os.Build

/**
 * Launch options for a widget's configuration activity. `startAppWidgetConfigureActivityForResult`
 * starts it through an IntentSender the system creates, and from Android 14 that counts as a
 * background start unless the app that sends it grants its own foreground privileges. AOSP tolerates
 * the start (the launcher is in front), but phones that enforce the rule to the letter — Samsung One
 * UI, #15 — showed nothing: the call returned normally and the widget stayed unconfigured. The
 * launcher is in the foreground whenever the user adds a widget, so the grant is its to give, as
 * Launcher3's allowBGLaunch does (Apache-2.0). Below 14 there is nothing to grant.
 *
 * Android 16 deprecates this mode in favour of ALLOW_ALWAYS but still maps it to the same full
 * grant, so one mode keeps 14–15 and 16+ on the same path, as the notification taps already do.
 */
internal fun widgetConfigLaunchOptions(): ActivityOptions? {
    // Read right here: lint's NewApi check recognises only a direct comparison as the version guard.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
    @Suppress("DEPRECATION")
    return ActivityOptions.makeBasic()
        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
}
