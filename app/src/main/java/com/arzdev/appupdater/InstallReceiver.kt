package com.arzdev.appupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.content.IntentSender

// Builds the IntentSender the PackageInstaller uses to report the result
// of an install session back to our InstallReceiver.
object PendingIntentFactory {
    const val ACTION = "com.arzdev.appupdater.INSTALL_RESULT"

    fun create(ctx: Context, packageName: String): IntentSender {
        val intent = Intent(ACTION)
        intent.setPackage(packageName) // must match our own package to be delivered
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(ctx, 1001, intent, flags)
        return pi.intentSender
    }
}

// Receives the install result broadcast and forwards it to Installer.Listener.
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = if (intent.hasExtra(PackageInstaller.EXTRA_STATUS)) {
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        } else PackageInstaller.STATUS_FAILURE

        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Successfully installed"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Confirm the install on screen"
            else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed (status $status)"
        }

        val l = InstallerListenerHolder.listener
        if (l != null) {
            l.onProgress(1f, message)
            l.onDone(status == PackageInstaller.STATUS_SUCCESS, message)
        }
    }
}
