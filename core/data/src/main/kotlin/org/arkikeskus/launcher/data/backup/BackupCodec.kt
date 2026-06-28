package org.arkikeskus.launcher.data.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * (De)serializes a [BackupDocument] to JSON using org.json. Settings values keep their JSON-native
 * types (boolean / number / string); the int-vs-float distinction is re-applied on import by
 * SettingsRepository's key registry, so the codec stays schema-agnostic.
 */
object BackupCodec {
    const val FORMAT = 1

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
                    .put("cellY", it.cellY),
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
        if (format != FORMAT) throw BackupFormatException("Unsupported backup format: $format")

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
                ),
            )
        }
        return BackupDocument(format, root.getString("appVersion"), root.getLong("createdAt"), settings, items)
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else getString(name)
}
