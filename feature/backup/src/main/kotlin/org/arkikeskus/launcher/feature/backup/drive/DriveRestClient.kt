package org.arkikeskus.launcher.feature.backup.drive

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

data class DriveFile(val id: String, val name: String, val modifiedTime: String)

/** Minimal Drive v3 REST client scoped to appDataFolder. [token] is a fresh OAuth access token. */
class DriveRestClient(
    private val token: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private fun authed(b: Request.Builder) = b.header("Authorization", "Bearer $token")

    fun upload(name: String, json: String) {
        val meta = JSONObject()
            .put("name", name)
            .put("parents", org.json.JSONArray().put("appDataFolder"))
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(json.toRequestBody("application/json".toMediaType()))
            .build()
        val req = authed(Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .post(body)).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw it.toError("upload") }
    }

    fun list(): List<DriveFile> {
        val req = authed(Request.Builder().url(
            "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder" +
                "&orderBy=modifiedTime%20desc&fields=files(id,name,modifiedTime)",
        )).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toError("list")
            val files = JSONObject(resp.body?.string().orEmpty()).getJSONArray("files")
            return (0 until files.length()).map { i ->
                val o = files.getJSONObject(i)
                DriveFile(o.getString("id"), o.getString("name"), o.optString("modifiedTime"))
            }
        }
    }

    fun download(id: String): String {
        val req = authed(Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$id?alt=media")).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toError("download")
            return resp.body?.string().orEmpty()
        }
    }

    fun delete(id: String) {
        val req = authed(Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$id").delete()).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw it.toError("delete") }
    }

    fun pruneToNewest(keep: Int) {
        list().drop(keep).forEach { delete(it.id) }
    }

    /** Builds an [IOException] including the HTTP status and the API's error body (the body explains
     *  WHY, e.g. "Google Drive API has not been used in project … or it is disabled"). */
    private fun okhttp3.Response.toError(op: String): IOException {
        val detail = runCatching { body?.string() }.getOrNull()?.take(300)?.trim().orEmpty()
        return IOException("Drive $op HTTP $code${if (detail.isNotEmpty()) ": $detail" else ""}")
    }
}
