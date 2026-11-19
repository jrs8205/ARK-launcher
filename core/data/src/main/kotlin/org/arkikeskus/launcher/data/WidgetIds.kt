package org.arkikeskus.launcher.data

import android.appwidget.AppWidgetHost
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The id of the launcher's own widget host. Widget ids belong to this id, not to a host object. */
const val LAUNCHER_APPWIDGET_HOST_ID = 0x41524B31

/**
 * Frees widget ids of the launcher's host, unbinding the widgets: for rows that are deleted where no home
 * screen is there to do it (a home profile that is not showing). A row deleted on the home screen is freed
 * there, by the host the screen shows its widgets with.
 */
fun interface WidgetIdReleaser {
    fun release(appWidgetId: Int)
}

/** [WidgetIdReleaser] on the system: a second host object on the launcher's host id, as the confirmation of
 *  another app's widget request has (ids are the host id's, not the object's). */
@Singleton
class HostWidgetIdReleaser @Inject constructor(@ApplicationContext context: Context) : WidgetIdReleaser {
    private val host by lazy { AppWidgetHost(context.applicationContext, LAUNCHER_APPWIDGET_HOST_ID) }

    override fun release(appWidgetId: Int) {
        runCatching { host.deleteAppWidgetId(appWidgetId) }
    }
}
