package com.arzdev.appupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.content.IntentSender
import android.os.Build

// Builds the IntentSender the PackageInstaller uses to report the result
// of an install session back to our InstallReceiver.
object PendingIntentFactory {
    const val ACTION = "com.arzdev.appupdater.INSTALL_RESULT"

    fun create(ctx: Context, packageName: String, sessionId: Int): IntentSender {
        val intent = Intent(ctx, InstallReceiver::class.java).apply {
            action = ACTION
            setPackage(packageName)
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        }
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else 0
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
        val pi = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
        return pi.intentSender
    }
}

// Receives the install result broadcast and forwards it to Installer.Listener.
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (!InstallerListenerHolder.isActiveSession(sessionId)) return

        val status = if (intent.hasExtra(PackageInstaller.EXTRA_STATUS)) {
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        } else PackageInstaller.STATUS_FAILURE

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            }

            if (confirmation != null) {
                InstallerListenerHolder.listener?.onStatus("Confirm the install on screen")
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmation)
                } catch (e: Exception) {
                    finish(false, "Could not open install confirmation: ${e.message}")
                }
            } else {
                finish(false, "Install confirmation was required but unavailable")
            }
            return
        }

        val success = status == PackageInstaller.STATUS_SUCCESS
        val message = if (success) {
            "Successfully installed"
        } else {
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?: "Install failed (status $status)"
        }
        finish(success, message)
    }

    private fun finish(success: Boolean, message: String) {
        val listener = InstallerListenerHolder.listener ?: return
        if (InstallerListenerHolder.currentCompletion().compareAndSet(false, true)) {
            listener.onProgress(1f, message)
            listener.onDone(success, message)
        }
    }
}
