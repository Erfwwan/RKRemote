package com.rkremote.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restores only the owner-approved LAN agent after boot.
 * Camera, microphone, screen capture and PTT playback are never restarted here.
 */
class BootStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SessionCredentialStore.isAutoStartAfterBoot(context)) return
        if (SessionCredentialStore.activeToken(context) == null) return

        // Never put the decrypted bearer token in a broadcast/service Intent.
        // The service reads it directly from the Keystore-encrypted store.
        val serviceIntent = Intent(context, SessionForegroundService::class.java)
            .setAction(SessionForegroundService.ACTION_RESTORE_LAN)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (error: Exception) {
            // Some OEM/OS states can reject background foreground-service starts.
            // Leave the saved owner preference intact so the owner can retry manually.
            Log.w(TAG, "Boot restoration was not allowed by the system", error)
        }
    }

    companion object {
        private const val TAG = "RKRemoteBoot"
    }
}
