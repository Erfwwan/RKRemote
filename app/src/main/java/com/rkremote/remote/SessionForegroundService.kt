package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rkremote.MainActivity
import com.rkremote.R

/**
 * Visible foreground indicator for an active, user-approved remote session.
 * The persistent notification provides a local stop action.
 */
class SessionForegroundService : Service() {\n    private var lanServer: LanAgentServer? = null
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lanServer?.stop()
            lanServer = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (intent?.action == ACTION_START_LAN) {
            val token = intent.getStringExtra(EXTRA_TOKEN)
            if (token.isNullOrBlank() || token.length < 24) {
                stopSelf()
                return START_NOT_STICKY
            }
            lanServer?.stop()
            lanServer = LanAgentServer(applicationContext, token).also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lanServer?.stop()
        lanServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active remote session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible status and stop control for an active RKRemote session"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("RKRemote session active")
            .setContentText("Remote access is active on this device.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop session", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.rkremote.action.STOP_SESSION"
        const val ACTION_START_LAN = "com.rkremote.action.START_LAN"
        const val EXTRA_TOKEN = "session_token"
        private const val CHANNEL_ID = "rkremote_active_session"
        private const val NOTIFICATION_ID = 4201
    }
}
