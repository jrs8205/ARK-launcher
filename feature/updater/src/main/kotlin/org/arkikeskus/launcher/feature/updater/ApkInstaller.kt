package org.arkikeskus.launcher.feature.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject

class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val installing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Downloads the APK to cache and launches the system installer (or routes the user to grant the
     *  "install unknown apps" permission; falls back to opening the release page in the browser). */
    suspend fun downloadAndInstall(info: UpdateInfo, onProgress: (Int) -> Unit = {}) {
        if (!installing.compareAndSet(false, true)) return
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                // Route to the per-app "install unknown apps" setting; user returns and taps Update again.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { openReleasePage() }
                return
            }
            val file = withContext(Dispatchers.IO) { download(info, onProgress) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure { openReleasePage() }
        } finally {
            installing.set(false)
        }
    }

    private fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Stable name so repeated taps reuse/overwrite rather than accumulate.
        val out = File(dir, "update.apk")
        val req = Request.Builder().url(info.apkUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("download HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val total = body.contentLength()
            // The release API tells us the size; allow slack but never write unboundedly (a wrong or
            // corrupt response must not fill the cache).
            val cap = if (info.sizeBytes > 0) info.sizeBytes + 1_048_576 else MAX_APK_BYTES
            var downloaded = 0L
            onProgress(0)
            out.outputStream().use { o ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        downloaded += n
                        if (downloaded > cap) throw IOException("APK exceeds expected size")
                        o.write(buf, 0, n)
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            onProgress(100)
        }
        validateApk(out)
        return out
    }

    /** Rejects a download whose package doesn't match this app, so a wrong asset never reaches the
     *  installer. Signature and downgrade are enforced by the OS installer; this catches gross
     *  mismatch (wrong app, corrupt file) before we even show the install prompt. */
    private fun validateApk(file: File) {
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("Not a valid APK")
        val expected = context.packageName.removeSuffix(".debug")
        if (archive.packageName != expected) throw IOException("Unexpected package ${archive.packageName}")
    }

    private fun openReleasePage() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private companion object {
        const val RELEASE_PAGE_URL = "https://github.com/jrs8205/ARK-launcher/releases/latest"

        /** Hard ceiling when the release API reports no asset size (a real APK is a few MB). */
        const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}
