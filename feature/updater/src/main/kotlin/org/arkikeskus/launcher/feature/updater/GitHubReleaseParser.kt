package org.arkikeskus.launcher.feature.updater

import org.json.JSONObject

data class ParsedRelease(val tag: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

/** Parses a GitHub `releases/latest` JSON payload; null if it carries no .apk asset. */
object GitHubReleaseParser {
    fun parse(json: String): ParsedRelease? {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").ifEmpty { return null }
        val notes = root.optString("body")
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val url = a.optString("browser_download_url").ifEmpty { continue }
                return ParsedRelease(tag, notes, url, a.optLong("size"))
            }
        }
        return null
    }
}
