package org.arkikeskus.launcher

import android.os.Bundle

/** Request code of the system-routed configuration of a widget being added (see LauncherActivity). */
internal const val WIDGET_CONFIG_REQUEST = 0x4357

/**
 * Request code of a placed widget's re-configuration. Its OWN code: a cancelled reconfigure must
 * never reach the pending-bind result handling, which would delete a pending bind's id.
 */
internal const val WIDGET_RECONFIGURE_REQUEST = 0x4358

/** Whether [requestCode] is the add-flow configuration whose result the home screen waits for. */
internal fun isWidgetConfigResult(requestCode: Int): Boolean = requestCode == WIDGET_CONFIG_REQUEST

/**
 * Starts [appWidgetId]'s configuration activity again through the host ([start] is the host's
 * `startAppWidgetConfigureActivityForResult`, which starts it on the provider's behalf, so a
 * configuration activity that is not exported opens too; a raw ACTION_APPWIDGET_CONFIGURE intent is
 * refused there). With the Android 14+ background-start grant, like the first configuration. False
 * when the host refused; the caller then falls back.
 */
internal fun startWidgetReconfigure(
    appWidgetId: Int,
    start: (appWidgetId: Int, requestCode: Int, options: Bundle?) -> Unit,
): Boolean = runCatching { start(appWidgetId, WIDGET_RECONFIGURE_REQUEST, widgetConfigLaunchOptions()?.toBundle()) }.isSuccess
