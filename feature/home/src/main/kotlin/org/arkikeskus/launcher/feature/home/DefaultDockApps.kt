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

    fun defaultPackageFor(intent: Intent): String? =
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            // "android" is the chooser/resolver — there is no single default app to pin.
            ?.takeIf { it != "android" }

    val slots = listOf(
        defaultPackageFor(Intent(Intent.ACTION_DIAL)),
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull(),
        defaultPackageFor(Intent(Settings.ACTION_SETTINGS)),
        defaultPackageFor(Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"))),
        defaultPackageFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
    )
    return slots
        .mapNotNull { pkg ->
            pkg?.let { p ->
                // The dock stores drawer-app keys; prefer the main profile's entry of the package.
                apps.filter { it.packageName == p }.minByOrNull { it.userSerial }?.key
            }
        }
        .distinct()
}
