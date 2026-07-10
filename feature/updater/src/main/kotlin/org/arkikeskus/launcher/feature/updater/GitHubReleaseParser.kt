package org.arkikeskus.launcher.feature.updater

import org.json.JSONObject

data class ParsedRelease(val tag: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

/** Parses a GitHub `releases/latest` JSON payload; null if it carries no release APK asset. */
object GitHubReleaseParser {
    // The release asset is named `ARK-launcher-X.Y.Z.apk` (historically `arkikeskus-launcher-X.Y.Z.apk`).
    // Match that exact shape so a stray debug/split/mapping APK in a release is never picked.
    private val RELEASE_APK = Regex("""(?i)^(ark|arkikeskus)-launcher-\d+\.\d+\.\d+\.apk$""")

    fun parse(json: String): ParsedRelease? {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").ifEmpty { return null }
        val notes = root.optString("body")
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (RELEASE_APK.matches(a.optString("name"))) {
                val url = a.optString("browser_download_url").ifEmpty { continue }
                return ParsedRelease(tag, notes, url, a.optLong("size"))
            }
        }
        return null
    }
}
