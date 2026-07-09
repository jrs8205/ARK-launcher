package org.arkikeskus.launcher.feature.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

private const val LATEST_URL =
    "https://api.github.com/repos/jrs8205/ARK-launcher/releases/latest"

class UpdateRepository @Inject constructor(private val http: OkHttpClient) {

    /** Fetches the latest release and returns an [UpdateInfo] only if it is newer than [currentVersionName]. */
    suspend fun checkLatest(currentVersionName: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        decide(currentVersionName, GitHubReleaseParser.parse(body))
    }

    /** Pure decision: newer parsed release → UpdateInfo, else null. */
    fun decide(currentVersionName: String, parsed: ParsedRelease?): UpdateInfo? {
        if (parsed == null) return null
        if (!SemVer.isNewer(parsed.tag, currentVersionName)) return null
        return UpdateInfo(parsed.tag.removePrefix("v").removePrefix("V"), parsed.notes, parsed.apkUrl, parsed.sizeBytes)
    }
}
