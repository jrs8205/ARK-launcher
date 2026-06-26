package org.arkikeskus.launcher.data.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.arkikeskus.launcher.data.R
import org.arkikeskus.launcher.model.SearchResult
import javax.inject.Inject

/** A curated, localizable settings page entry. */
data class SettingItem(
    val key: String,
    val action: String,
    val label: String,
    val aliases: List<String>,
)

/** Pure matcher: a [SettingItem] matches when the trimmed query is a substring of its label or any alias. */
fun matchSettings(items: List<SettingItem>, query: String): List<SearchResult.Setting> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return items.filter { item ->
        item.label.lowercase().contains(q) || item.aliases.any { it.lowercase().contains(q) }
    }.map { SearchResult.Setting(it.label, it.action, it.key) }
}

/**
 * Searches a curated set of common system settings pages. No public API enumerates all settings
 * without system privileges, so this is a hand-maintained list with localized labels + bilingual
 * keyword aliases. Always enabled, no permission.
 */
class SettingsSearchProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SearchProvider {

    override suspend fun isEnabled(): Boolean = true

    override suspend fun query(query: String): List<SearchResult> = matchSettings(items(), query)

    private fun items(): List<SettingItem> = CURATED.map { (key, action, labelRes, aliasRes) ->
        SettingItem(
            key = key,
            action = action,
            label = context.getString(labelRes),
            aliases = context.getString(aliasRes).split(",").map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    private companion object {
        // key, intent action, label resId, comma-separated aliases resId
        val CURATED = listOf(
            Quad("wifi", "android.settings.WIFI_SETTINGS", R.string.setting_wifi, R.string.setting_wifi_aliases),
            Quad("bluetooth", "android.settings.BLUETOOTH_SETTINGS", R.string.setting_bluetooth, R.string.setting_bluetooth_aliases),
            Quad("network", "android.settings.WIRELESS_SETTINGS", R.string.setting_network, R.string.setting_network_aliases),
            Quad("data", "android.settings.DATA_USAGE_SETTINGS", R.string.setting_data, R.string.setting_data_aliases),
            Quad("battery", "android.settings.BATTERY_SAVER_SETTINGS", R.string.setting_battery, R.string.setting_battery_aliases),
            Quad("display", "android.settings.DISPLAY_SETTINGS", R.string.setting_display, R.string.setting_display_aliases),
            Quad("sound", "android.settings.SOUND_SETTINGS", R.string.setting_sound, R.string.setting_sound_aliases),
            Quad("apps", "android.settings.APPLICATION_SETTINGS", R.string.setting_apps, R.string.setting_apps_aliases),
            Quad("storage", "android.settings.INTERNAL_STORAGE_SETTINGS", R.string.setting_storage, R.string.setting_storage_aliases),
            Quad("location", "android.settings.LOCATION_SOURCE_SETTINGS", R.string.setting_location, R.string.setting_location_aliases),
            Quad("datetime", "android.settings.DATE_SETTINGS", R.string.setting_datetime, R.string.setting_datetime_aliases),
            Quad("security", "android.settings.SECURITY_SETTINGS", R.string.setting_security, R.string.setting_security_aliases),
            Quad("accessibility", "android.settings.ACCESSIBILITY_SETTINGS", R.string.setting_accessibility, R.string.setting_accessibility_aliases),
            Quad("language", "android.settings.LOCALE_SETTINGS", R.string.setting_language, R.string.setting_language_aliases),
            Quad("about", "android.settings.DEVICE_INFO_SETTINGS", R.string.setting_about, R.string.setting_about_aliases),
            Quad("systemupdate", "android.settings.SYSTEM_UPDATE_SETTINGS", R.string.setting_systemupdate, R.string.setting_systemupdate_aliases),
        )
    }
}

private data class Quad(val key: String, val action: String, val labelRes: Int, val aliasRes: Int)
