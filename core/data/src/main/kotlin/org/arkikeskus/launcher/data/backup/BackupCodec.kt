package org.arkikeskus.launcher.data.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * (De)serializes a [BackupDocument] to JSON using org.json. Settings values keep their JSON-native
 * types (boolean / number / string); the int-vs-float distinction is re-applied on import by
 * SettingsRepository's key registry, so the codec stays schema-agnostic.
 */
object BackupCodec {
    /** Format 2 added widget rows (spanX/spanY/widgetProvider); format 3 adds built-in widgets
     *  (builtinType). Decode still accepts every older format, absent fields defaulting. */
    const val FORMAT = 3

    fun encode(doc: BackupDocument): String {
        val settings = JSONObject()
        for ((k, v) in doc.settings) settings.put(k, v)

        val items = JSONArray()
        for (it in doc.homeItems) {
            items.put(
                JSONObject()
                    .put("id", it.id)
                    .put("containerId", it.containerId)
                    .put("folderName", it.folderName ?: JSONObject.NULL)
                    .put("packageName", it.packageName)
                    .put("className", it.className)
                    .put("mainProfile", it.mainProfile)
                    .put("shortcutId", it.shortcutId ?: JSONObject.NULL)
                    .put("page", it.page)
                    .put("cellX", it.cellX)
                    .put("cellY", it.cellY)
                    .put("spanX", it.spanX)
                    .put("spanY", it.spanY)
                    .put("widgetProvider", it.widgetProvider ?: JSONObject.NULL)
                    .put("builtinType", it.builtinType ?: JSONObject.NULL),
            )
        }

        return JSONObject()
            .put("format", doc.format)
            .put("appVersion", doc.appVersion)
            .put("createdAt", doc.createdAt)
            .put("settings", settings)
            .put("homeItems", items)
            .toString()
    }

    fun decode(json: String): BackupDocument {
        val root = JSONObject(json)
        if (!root.has("format")) throw BackupFormatException("Missing 'format'")
        val format = root.getInt("format")
        // Accept any known format up to the current one: format 1 (no widget fields) still restores,
        // its widget columns defaulting below. A newer-than-known format is rejected.
        if (format < 1 || format > FORMAT) throw BackupFormatException("Unsupported backup format: $format")

        val settingsObj = root.getJSONObject("settings")
        val settings = LinkedHashMap<String, Any>()
        for (key in settingsObj.keys()) settings[key] = settingsObj.get(key)

        val itemsArr = root.getJSONArray("homeItems")
        val items = ArrayList<BackupItem>(itemsArr.length())
        for (i in 0 until itemsArr.length()) {
            val o = itemsArr.getJSONObject(i)
            items.add(
                BackupItem(
                    id = o.getLong("id"),
                    containerId = o.getLong("containerId"),
                    folderName = o.optStringOrNull("folderName"),
                    packageName = o.getString("packageName"),
                    className = o.getString("className"),
                    mainProfile = o.getBoolean("mainProfile"),
                    shortcutId = o.optStringOrNull("shortcutId"),
                    page = o.getInt("page"),
                    cellX = o.getInt("cellX"),
                    cellY = o.getInt("cellY"),
                    // Widget columns are absent in format 1 → default to a 1×1 non-widget row;
                    // builtinType is absent before format 3 → a plain row.
                    spanX = o.optInt("spanX", 1),
                    spanY = o.optInt("spanY", 1),
                    widgetProvider = o.optStringOrNull("widgetProvider"),
                    builtinType = o.optStringOrNull("builtinType"),
                ),
            )
        }
        return BackupDocument(format, root.getString("appVersion"), root.getLong("createdAt"), settings, items)
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else getString(name)
}
