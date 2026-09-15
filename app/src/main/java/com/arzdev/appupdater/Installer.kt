package com.arzdev.appupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
        // Route through HttpHelper so a NetGuard DNS failure falls back to the
        // funnel's known public IPs (with correct Host header + SNI).
        val path = "/uploads/" + info.url.substringAfterLast('/')
        val conn = HttpHelper.openHttps(path)
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-Api-Key", Api.API_KEY)
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 AppUpdater/1.4")
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
            return
        } finally {
            try { session.close() } catch (_: Exception) {}
        }

        // ⚠️ Completion detection — made box-independent (v1.4.3+):
        // Some Fire OS / Android TV builds never deliver the PackageInstaller result
        // broadcast, AND the version-poll's getPackageInfo can keep returning a stale
        // "not installed" for up to 90s (cache not refreshed until the PACKAGE_ADDED
        // broadcast). The reliable signal that ALWAYS fires when a package installs is
        // the system-wide Intent.ACTION_PACKAGE_ADDED. We register a dynamic receiver
        // for it and use it as the primary completion signal, keeping the version-poll
        // (which the PACKAGE_ADDED receiver refreshes) as a fallback.
        val completion = InstallerListenerHolder.newCompletion()
        val targetVc = info.versionCode
        val pkgName = info.packageName

        val addedReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val added = intent?.data?.encodedSchemeSpecificPart
                if (pkgName != null && added != null && added.equals(pkgName, ignoreCase = true)) {
                    // Confirmed installed — refresh package cache + report success once.
                    refreshPackageCache(pm, pkgName, targetVc, info, listener, completion)
                }
            }
        }
        // Match any installed package (matches intent-filter data scheme "package").
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED)
        filter.addDataScheme("package")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(addedReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(addedReceiver, filter)
            }
        } catch (_: Exception) {
            // Polling below remains a fallback if receiver registration fails.
        }

        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            // If PACKAGE_ADDED already delivered a result, stop polling.
            if (completion.get()) {
                try { ctx.unregisterReceiver(addedReceiver) } catch (_: Exception) {}
                return
            }
            if (pkgName == null) {
                // No package metadata — rely on the broadcast filter (matches any pkg).
                Thread.sleep(2000)
                continue
            }
            refreshPackageCache(pm, pkgName, targetVc, info, listener, completion)
            if (completion.get()) {
                try { ctx.unregisterReceiver(addedReceiver) } catch (_: Exception) {}
                return
            }
            Thread.sleep(2000)
        }
        // Timed out — unregister and do a final best-effort check before erroring.
        try { ctx.unregisterReceiver(addedReceiver) } catch (_: Exception) {}
        if (completion.compareAndSet(false, true)) {
            listener.onDone(false, "${info.label}: install not confirmed in 90s. Check the device.")
        }
    }

    // Refresh the installed-version check once. Reports onDone(true) if the package
    // is now installed and at least targetVc (or no known target). Claims completion
    // once via the shared AtomicBoolean so PACKAGE_ADDED and the poll never double-fire.
    private fun refreshPackageCache(
        pm: PackageManager, pkgName: String, targetVc: Long?,
        info: ApkInfo, listener: Listener, completion: java.util.concurrent.atomic.AtomicBoolean
    ) {
        try {
            val pi = pm.getPackageInfo(pkgName, 0)
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            if (targetVc == null || installed >= targetVc) {
                if (completion.compareAndSet(false, true)) {
                    listener.onDone(true, "${info.label} installed (v${pi.versionName ?: installed})")
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            // not installed (or cache stale) — keep waiting
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
