package org.arkikeskus.launcher.data

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/**
 * The request behind a `LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT` or `ACTION_CONFIRM_PIN_APPWIDGET`
 * intent as a [PinRequest] (a [WidgetPinRequest] for a widget), or null when the intent carries
 * none, the request is of an unknown kind, or its profile or provider can't be resolved. The
 * shortcut's label is the one home will show ([PinItem.Shortcut.label]).
 *
 * A request from a profile [isPrivateProfile] names (the private space) is dismissed the same way,
 * before anything of it is looked up: a pinned shortcut or widget is a home row, written to the
 * database, and nothing of the private space may be.
 */
fun systemPinRequest(context: Context, intent: Intent, isPrivateProfile: (UserHandle) -> Boolean): PinRequest? {
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
    val request = runCatching { launcherApps.getPinItemRequest(intent) }.getOrNull() ?: return null
    return when (request.requestType) {
        LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> shortcutRequest(context, launcherApps, request, isPrivateProfile)
        LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> widgetRequest(context, request, isPrivateProfile)
        else -> null
    }
}

private fun shortcutRequest(
    context: Context,
    launcherApps: LauncherApps,
    request: LauncherApps.PinItemRequest,
    isPrivateProfile: (UserHandle) -> Boolean,
): PinRequest? {
    val requested = runCatching { request.shortcutInfo }.getOrNull() ?: return null
    if (isPrivateProfile(requested.userHandle)) return null
    // The confirmation is an exported activity, so the request may be one another app made up: a
    // shortcut the system has (published, or pinned already) shows as the system has it. One only the
    // request carries (a browser's page) shows as requested, and is stored only if the system pins it.
    val info = runCatching { systemShortcut(launcherApps, requested) }.getOrNull() ?: requested
    val userManager = context.getSystemService(UserManager::class.java) ?: return null
    val serial = runCatching { userManager.getSerialNumberForUser(info.userHandle) }.getOrNull()
        ?.takeIf { it >= 0 } ?: return null
    // Same rule as AppShortcuts.resolve, so the preview shows the name the home screen will.
    val label = (info.longLabel ?: info.shortLabel)?.toString() ?: info.id
    return SystemPinRequest(request, info, PinItem.Shortcut(info.`package`, info.id, serial, label))
}

private fun widgetRequest(
    context: Context,
    request: LauncherApps.PinItemRequest,
    isPrivateProfile: (UserHandle) -> Boolean,
): PinRequest? {
    val claimed = runCatching { request.getAppWidgetProviderInfo(context) }.getOrNull() ?: return null
    val provider = claimed.provider ?: return null
    // The confirmation is an exported activity, so the request may be one another app made up rather
    // than the system's: the widget is the installed provider it names, as the system describes it
    // (the widget picker's own description), or nothing.
    val profile = runCatching { claimed.profile }.getOrNull() ?: Process.myUserHandle()
    if (isPrivateProfile(profile)) return null
    val info = runCatching {
        AppWidgetManager.getInstance(context).getInstalledProvidersForPackage(provider.packageName, profile)
    }.getOrNull()?.firstOrNull { it.provider == provider } ?: return null
    // The same name the widget picker shows for it.
    val label = runCatching { info.loadLabel(context.packageManager) }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: provider.packageName
    val ownProfile = info.profile == null || info.profile == Process.myUserHandle()
    return SystemWidgetPinRequest(request, info, PinItem.Widget(provider.flattenToString(), label, ownProfile))
}

/** The system's own [requested] shortcut: one its app published or this launcher pinned, else null. */
private fun systemShortcut(launcherApps: LauncherApps, requested: ShortcutInfo): ShortcutInfo? {
    val query = LauncherApps.ShortcutQuery()
        .setPackage(requested.`package`)
        .setShortcutIds(listOf(requested.id))
        .setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
        )
    return launcherApps.getShortcuts(query, requested.userHandle)?.firstOrNull { it.id == requested.id }
}

/** The requesting app's name in [user]'s profile (a work app's badge-less label), or its package. */
private fun appLabel(context: Context, packageName: String, user: UserHandle): String {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    return runCatching {
        launcherApps?.getApplicationInfo(packageName, 0, user)?.loadLabel(context.packageManager)?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
}

private class SystemPinRequest(
    private val request: LauncherApps.PinItemRequest,
    private val info: ShortcutInfo,
    override val item: PinItem.Shortcut,
) : PinRequest {
    override val isValid: Boolean get() = request.isValid

    override fun accept(): Boolean = request.accept()

    override fun loadPreview(context: Context): PinPreview {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val density = context.resources.displayMetrics.densityDpi
        val icon = runCatching {
            launcherApps?.getShortcutIconDrawable(info, density)?.toBitmap(PREVIEW_ICON_PX)
        }.getOrNull()
        return PinPreview(appLabel(context, info.`package`, info.userHandle), icon)
    }

    private fun Drawable.toBitmap(sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, sizePx, sizePx)
        draw(Canvas(bitmap))
        return bitmap
    }

    private companion object {
        /** The raster size the home screen uses for a pinned shortcut's icon (AppShortcuts.resolve). */
        const val PREVIEW_ICON_PX = 168
    }
}

private class SystemWidgetPinRequest(
    private val request: LauncherApps.PinItemRequest,
    override val info: AppWidgetProviderInfo,
    override val item: PinItem.Widget,
) : WidgetPinRequest {
    override val isValid: Boolean get() = request.isValid

    /** A widget is accepted with its id; the plain accept would leave it unbound. */
    override fun accept(): Boolean = false

    override fun accept(appWidgetId: Int): Boolean =
        request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) })

    /** The widget has its own preview (the picker's); only the requesting app's name is read here. */
    override fun loadPreview(context: Context): PinPreview =
        PinPreview(appLabel(context, info.provider.packageName, info.profile ?: Process.myUserHandle()), icon = null)
}
