package com.arzdev.appupdater

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject

data class ApkInfo(
    val filename: String,
    val size: Long,
    val displayName: String,
    val label: String,
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val url: String
)

object Api {
    // Backend — same APK Installer server the web tool + field app use.
    const val BASE_URL = "https://apk-installer.tailc8cd81.ts.net"
    const val API_KEY = "2dd76b09cec82bed95fad74f42987855a86507e8446c164c"

    private fun applyAuth(conn: HttpURLConnection) {
        conn.setRequestProperty("X-Api-Key", API_KEY)
        // Server blocks bot UAs for unauthenticated requests; our key auths us,
        // but send a normal browser UA anyway to be safe against UA sniffing.
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppUpdater/1.0")
    }

    fun fetchLibrary(): List<ApkInfo> {
        // Route through HttpHelper so a NetGuard DNS failure falls back to the
        // funnel's known public IPs (with correct Host header + SNI).
        val conn = HttpHelper.openHttps("/api/apks")
        conn.requestMethod = "GET"
        applyAuth(conn)

        try {
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP error $responseCode: ${conn.responseMessage}")
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()

            val json = JSONObject(sb.toString())
            if (!json.optBoolean("ok", false)) {
                throw Exception("API response ok != true")
            }

            val apksArray = json.optJSONArray("apks") ?: return emptyList()
            val list = mutableListOf<ApkInfo>()

            for (i in 0 until apksArray.length()) {
                val obj = apksArray.getJSONObject(i)
                val filename = obj.getString("filename")
                val size = obj.optLong("size", 0L)
                val displayName = obj.optString("displayName", "").ifEmpty { filename }
                val label = obj.optString("label", "").ifEmpty { displayName }
                val pkg = obj.optString("package", "").ifEmpty { null }
                val vc = if (obj.has("versionCode") && !obj.isNull("versionCode")) obj.optLong("versionCode") else null
                val vn = obj.optString("versionName", "").ifEmpty { null }
                val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                val apkUrl = "$BASE_URL/uploads/$encoded"
                list.add(ApkInfo(filename, size, displayName, label, pkg, vc, vn, apkUrl))
            }
            // Sort: apps with packages (installable) first, then by label
            return list.sortedWith(compareBy({ it.packageName == null }, { it.label.lowercase(Locale.US) }))
        } finally {
            conn.disconnect()
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${bytes / 1024} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }
}
