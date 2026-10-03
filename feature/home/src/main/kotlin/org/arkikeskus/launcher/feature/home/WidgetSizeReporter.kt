package org.arkikeskus.launcher.feature.home

/**
 * Remembers the size last reported to each hosted widget's provider. Every report makes the system
 * broadcast APPWIDGET_UPDATE_OPTIONS and the provider rebuild its RemoteViews (a Glance widget whose
 * session has ended starts a new one), and the page container's update block runs on every page
 * (re)composition — up to every frame — so only a real size change may reach the provider.
 * Re-sending an unchanged size made widgets flash empty after a page change. Launcher3 likewise
 * reports a widget's size only on bind and resize.
 */
internal class WidgetSizeReporter {
    private val lastSent = HashMap<Int, Pair<Int, Int>>()

    /** Runs [send] unless ([wDp], [hDp]) was already sent for [appWidgetId]; a send that throws is
     *  not recorded, so the next call retries it. */
    fun report(appWidgetId: Int, wDp: Int, hDp: Int, send: () -> Unit) {
        val size = wDp to hDp
        if (lastSent[appWidgetId] == size) return
        runCatching(send).onSuccess { lastSent[appWidgetId] = size }
    }

    fun forget(appWidgetId: Int) {
        lastSent.remove(appWidgetId)
    }
}
