package com.digimax.transcriber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app self-update. The app's own audio never leaves the phone — the only network
 * use is this: fetch a tiny public version.json from the repo, and if it names a newer
 * build, download that release APK and hand it to the system installer.
 *
 * Integrity is enforced by Android itself: an upgrade install is rejected unless the
 * downloaded APK is signed with the SAME release key as the installed app. A tampered
 * or wrong-key APK cannot replace this one. The feed + asset are HTTPS-only.
 */
object Updater {
    // Public, unauthenticated, stable. (record.digimax.mu is OTP-gated, so unusable here.)
    private const val FEED =
        "https://raw.githubusercontent.com/divergentinc2021/digimax-transcriber-android/main/version.json"

    data class Update(val code: Int, val name: String, val url: String, val notes: String)

    /** Returns an Update only when the feed names a versionCode newer than this build; else null. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val txt = fetchText(FEED) ?: return@withContext null
            val o = JSONObject(txt)
            val code = o.getInt("versionCode")
            if (code <= BuildConfig.VERSION_CODE) return@withContext null
            val url = o.getString("url")
            require(url.startsWith("https://")) { "update url is not https" }
            Update(code, o.optString("versionName"), url, o.optString("notes"))
        }.getOrNull()
    }

    private fun fetchText(u: String): String? {
        val conn = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000; instanceFollowRedirects = true
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK to cacheDir/updates, reporting 0..100. Returns the file, or null on failure. */
    suspend fun download(ctx: Context, up: Update, onPct: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "digimax-${up.name.ifBlank { up.code.toString() }}.apk")
                val conn = (URL(up.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext null }
                val total = conn.contentLengthLong
                conn.inputStream.use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                        while (ins.read(buf).also { n = it } >= 0) {
                            os.write(buf, 0, n); done += n
                            if (total > 0) onPct(((done * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
                conn.disconnect()
                out
            }.getOrNull()
        }

    /**
     * Hands the APK to the system installer. On API 26+ the app needs the user's
     * "install unknown apps" permission first — if it's not granted, we route the
     * user to that settings screen and return; they grant it and tap Update again.
     */
    fun install(ctx: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
}
