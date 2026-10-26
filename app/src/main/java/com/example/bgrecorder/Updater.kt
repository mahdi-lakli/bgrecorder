package com.example.bgrecorder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the latest GitHub Release, downloads its APK and opens the Android installer.
 * The repository must be PUBLIC for the release check to work without a login.
 */
object Updater {

    // Change this if your GitHub username or repository name is different.
    private const val REPO = "mahdi-lakli/bgrecorder"

    data class Release(
        val versionCode: Long,
        val name: String,
        val notes: String,
        val apkUrl: String
    )

    fun installedVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun installedVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Run on a background thread. Returns null if there is no release (or no APK) yet. */
    fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            when (conn.responseCode) {
                200 -> {}
                404 -> return null
                else -> throw IOException("GitHub answered ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

            // Tags look like "v12": the number is the version code of that build.
            val tag = json.getString("tag_name")
            val code = tag.removePrefix("v").toLongOrNull() ?: return null

            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl == null) return null

            return Release(
                versionCode = code,
                name = json.optString("name", tag),
                notes = json.optString("body", ""),
                apkUrl = apkUrl
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Run on a background thread. Downloads the APK and reports progress (0-100). */
    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "update.apk")
        if (file.exists()) file.delete()

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("Download answered ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** Opens the Android installer for the downloaded APK. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
