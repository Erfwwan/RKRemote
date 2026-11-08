package com.rkremote.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Small lifecycle boundary used by the UI. A session is started only after
 * the local owner has approved it in the UI.
 */
class SessionController(private val context: Context) {
    fun startOwnerApprovedSession() {
        val intent = Intent(context, SessionForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopSession() {
        context.stopService(Intent(context, SessionForegroundService::class.java))
    }
}
