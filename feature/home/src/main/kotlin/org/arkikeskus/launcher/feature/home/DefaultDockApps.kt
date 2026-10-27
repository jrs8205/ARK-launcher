package org.arkikeskus.launcher.feature.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import org.arkikeskus.launcher.model.AppItem

/**
 * The first-run default dock, in the user-chosen order: phone, messages, system Settings,
 * browser, camera. Each slot resolves the device's DEFAULT handler and is silently skipped when
 * nothing resolves (e.g. a tablet without telephony), so the seed degrades gracefully.
 */
fun resolveDefaultDockKeys(context: Context, apps: List<AppItem>): List<String> {
    val pm = context.packageManager

    /** The default handler's (package, activity); the activity tells the handler's entries apart. */
    fun defaultFor(intent: Intent): Pair<String, String?>? =
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.let { it.packageName to it.name }
            // "android" is the chooser/resolver — there is no single default app to pin.
            ?.takeIf { it.first != "android" }

    val slots = listOf(
        defaultFor(Intent(Intent.ACTION_DIAL)),
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let { it to null },
        defaultFor(Intent(Settings.ACTION_SETTINGS)),
        defaultFor(Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"))),
        defaultFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
    )
    return slots
        .mapNotNull { slot ->
            slot?.let { (p, activity) ->
                // The dock stores drawer-app keys; prefer the main profile's entry of the package.
                // A package with several launcher entries (a dialer that also holds Contacts) gives
                // the entry the intent resolved to, not the alphabetically first one.
                val entries = apps.filter { it.packageName == p }
                (entries.firstOrNull { it.className == activity } ?: entries.minByOrNull { it.userSerial })?.key
            }
        }
        .distinct()
}
