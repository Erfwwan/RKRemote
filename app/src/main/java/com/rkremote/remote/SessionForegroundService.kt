package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rkremote.MainActivity

/**
 * Visible foreground owner for an explicitly started Cloudflare relay session.
 * Encrypted sessions are never silently reconstructed after process death.
 */
class SessionForegroundService : Service() {
    private var internetPeer: SecureInternetPeer? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_STOP_INTERNET) {
            stopInternetPeer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_START_INTERNET) {
            // Never restore legacy LAN agents or interpret a null/redelivered
            // Intent as permission to start any remote-access service.
            stopInternetPeer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val foregroundStart = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Secure Cloudflare relay session active"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Secure Cloudflare relay session active"))
            }
        }
        if (foregroundStart.isFailure) {
            val pending = pendingInternetPeer
            pendingInternetPeer = null
            runCatching { pending?.stopFromOwnerAction("Android could not start the visible session service") }
            Log.e(TAG, "Unable to start visible Internet session service", foregroundStart.exceptionOrNull())
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val incoming = pendingInternetPeer
        pendingInternetPeer = null
        if (incoming == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stopInternetPeer(clearPhoto = internetPeer != null)
        internetPeer = incoming
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopInternetPeer()
        val pending = pendingInternetPeer
        pendingInternetPeer = null
        runCatching { pending?.stopFromOwnerAction("The foreground session service was destroyed") }
        if (serviceInstance === this) serviceInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopInternetPeer(clearPhoto: Boolean = true) {
        val active = internetPeer
        internetPeer = null
        runCatching { active?.let(LocationSessionBridge::detach) }
        runCatching { active?.stopFromOwnerAction() }
        stopLocalMediaServices(clearPhoto)
    }

    /**
     * The session notification is also an emergency stop for media services.
     * Closing the relay must not leave a microphone, speaker, screen capture,
     * or an unsent camera photo running/retained locally.
     */
    private fun stopLocalMediaServices(clearPhoto: Boolean) {
        runCatching { stopService(Intent(this, CameraCaptureService::class.java)) }
        runCatching { stopService(Intent(this, ScreenCaptureService::class.java)) }
        runCatching { stopService(Intent(this, MicrophoneCaptureService::class.java)) }
        runCatching { stopService(Intent(this, PttPlaybackService::class.java)) }
        if (clearPhoto) runCatching { CameraFrameStore.clear() }
        runCatching { ScreenVideoStore.clear() }
        runCatching { MicrophoneChunkStore.clear() }
        runCatching { PttAudioQueue.clear() }
    }

    private fun stopInternetSessionAndUpdate() {
        stopInternetPeer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active secure session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible status and stop control for an active RKRemote Cloudflare session"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
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
            .setContentTitle("RKRemote secure session active")
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop session", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.rkremote.action.STOP_SESSION"
        const val ACTION_START_INTERNET = "com.rkremote.action.START_INTERNET_SESSION"
        const val ACTION_STOP_INTERNET = "com.rkremote.action.STOP_INTERNET_SESSION"
        private const val CHANNEL_ID = "rkremote_active_session"
        private const val NOTIFICATION_ID = 4201
        private const val TAG = "RKRemoteSession"

        @Volatile private var serviceInstance: SessionForegroundService? = null
        @Volatile private var pendingInternetPeer: SecureInternetPeer? = null

        /** Called only from the explicit Connect action after local owner action. */
        fun startInternetSession(context: Context, peer: SecureInternetPeer) {
            pendingInternetPeer?.stopFromOwnerAction("A newer connection attempt replaced this session")
            pendingInternetPeer = peer
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context, SessionForegroundService::class.java).setAction(ACTION_START_INTERNET)
                )
            } catch (failure: Throwable) {
                if (pendingInternetPeer === peer) pendingInternetPeer = null
                peer.close()
                throw failure
            }
        }

        fun stopInternetSession(context: Context) {
            val running = serviceInstance
            if (running == null) {
                val pending = pendingInternetPeer
                pendingInternetPeer = null
                runCatching { pending?.stopFromOwnerAction("نشست توسط صاحب دستگاه متوقف شد") }
                context.applicationContext.stopService(
                    Intent(context, SessionForegroundService::class.java)
                )
                return
            }
            running.stopInternetSessionAndUpdate()
        }
    }
}
