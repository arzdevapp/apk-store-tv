package com.arzdev.appupdater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object Installer {
    private const val TAG = "AppUpdater"

    interface Listener {
        fun onProgress(fraction: Float, label: String)
        fun onStatus(message: String)
        fun onDone(success: Boolean, message: String)
    }

    // ── Download APK to the TV's own external storage ─────────────
    private fun downloadFile(info: ApkInfo, listener: Listener, outFile: File) {
        val conn = URL(info.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-Api-Key", Api.API_KEY)
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 AppUpdater/1.0")
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw Exception("Download failed HTTP $code")
            }
            val total = conn.contentLengthLong
            val input: InputStream = conn.inputStream
            FileOutputStream(outFile).use { fos ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done: Long = 0
                while (input.read(buf).also { read = it } != -1) {
                    fos.write(buf, 0, read)
                    done += read
                    if (total > 0) {
                        listener.onProgress((done.toFloat() / total).coerceIn(0f, 1f), "Downloading ${info.label}")
                    }
                }
            }
            input.close()
        } finally {
            conn.disconnect()
        }
    }

    // ── Install an already-downloaded APK via PackageInstaller ────
    // Requires REQUEST_INSTALL_PACKAGES + "Allow install from this source"
    // granted for this app (we prompt once on first use).
    private fun installApk(ctx: Context, apkFile: File, info: ApkInfo, listener: Listener) {
        listener.onStatus("Installing ${info.label}…")
        val pm = ctx.packageManager
        val name = ctx.packageName
        val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pm.packageInstaller.createSession(sessionParams)
        val session = pm.packageInstaller.openSession(sessionId)
        try {
            val stream = session.openWrite("pkg", 0, apkFile.length())
            apkFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    stream.write(buf, 0, read)
                }
            }
            session.fsync(stream)
            stream.close()
            session.commit(PendingIntentFactory.create(ctx, name))
        } catch (e: Exception) {
            session.abandon()
            listener.onDone(false, "Install failed: ${e.message}")
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    fun canRequestUnknownSources(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return pm.canRequestPackageInstalls()
    }

    fun requestUnknownSources(ctx: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    // ── Full flow: download + install in a background thread ──────
    fun downloadAndInstall(ctx: Context, info: ApkInfo, listener: Listener) {
        thread(name = "apk-download") {
            try {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
                val safeName = info.filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val outFile = File(dir, "update_$safeName")

                listener.onProgress(0f, "Starting download…")
                downloadFile(info, listener, outFile)
                listener.onProgress(0.9f, "Downloaded — installing…")
                installApk(ctx, outFile, info, listener)
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndInstall error", e)
                listener.onDone(false, "Error: ${e.message}")
            }
        }
    }
}
