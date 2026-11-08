package com.rkremote.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restores only the owner-approved LAN agent after boot.
 * Camera, microphone, screen capture and other sensor services are never restarted here.
 */
class BootStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SessionCredentialStore.isAutoStartAfterBoot(context)) return
        val token = SessionCredentialStore.activeToken(context) ?: return

        val serviceIntent = Intent(context, SessionForegroundService::class.java)
            .setAction(SessionForegroundService.ACTION_START_LAN)
            .putExtra(SessionForegroundService.EXTRA_TOKEN, token)
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }
}
