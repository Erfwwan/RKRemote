package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rkremote.MainActivity

/**
 * Visible foreground owner for active LAN or secure Internet sessions.
 * A WebRTC session is kept alive while this service remains in the process;
 * it cannot be reconstructed after process death and is never silently resumed.
 */
class SessionForegroundService : Service() {
    private var lanServer: LanAgentServer? = null
    private var internetPeer: SecureInternetPeer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SessionCredentialStore.clearActiveSession(this)
            stopInternetPeer()
            lanServer?.stop()
            lanServer = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_INTERNET) {
            stopInternetPeer()
            if (lanServer == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            } else {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification("LAN remote session remains active")
                )
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_INTERNET) {
            val foregroundStart = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification("Secure Internet P2P session active"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification("Secure Internet P2P session active"))
                }
            }
            if (foregroundStart.isFailure) {
                pendingInternetPeer?.close()
                pendingInternetPeer = null
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
            stopInternetPeer()
            internetPeer = incoming
            // Preserve an independently active LAN agent's existing sticky
            // restoration behavior; never attempt to recreate a WebRTC peer.
            return if (lanServer != null) START_STICKY else START_NOT_STICKY
        }

        val token = when (intent?.action) {
            ACTION_START_LAN -> intent.getStringExtra(EXTRA_TOKEN)
            ACTION_RESTORE_LAN, null -> SessionCredentialStore.activeToken(this)
            else -> null
        }?.takeIf { it.length >= 24 }

        if (token == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_LAN) {
            runCatching { SessionCredentialStore.saveActiveSession(this, token) }
                .onFailure {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
        }

        val notification = buildNotification()
        val foregroundStart = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        if (foregroundStart.isFailure) {
            Log.e(TAG, "Unable to enter foreground state; session is retained for manual retry", foregroundStart.exceptionOrNull())
            // Keep the encrypted owner session for a later manual retry, but do not
            // leave a partially started service running without its foreground state.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        lanServer?.stop()
        lanServer = LanAgentServer(applicationContext, token) { reason ->
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification("LAN agent stopped · " + reason.take(100))
            )
        }.also { it.start() }

        // Do not ask Android to retain/redeliver the start Intent: it contains the
        // bearer token. START_STICKY recreates with a null Intent and restores only
        // from the Android-Keystore-encrypted session store.
        return START_STICKY
    }

    override fun onDestroy() {
        stopInternetPeer()
        lanServer?.stop()
        lanServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopInternetPeer() {
        val active = internetPeer
        internetPeer = null
        runCatching { active?.revokeOwnerApprovedCommands() }
        runCatching { active?.close() }
    }

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

    private fun buildNotification(statusText: String = "Remote access is active on this device."): Notification {
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
        const val ACTION_START_LAN = "com.rkremote.action.START_LAN"
        const val ACTION_RESTORE_LAN = "com.rkremote.action.RESTORE_LAN"
        const val EXTRA_TOKEN = "session_token"
        private const val CHANNEL_ID = "rkremote_active_session"
        private const val NOTIFICATION_ID = 4201
        private const val TAG = "RKRemoteSession"
        @Volatile private var pendingInternetPeer: SecureInternetPeer? = null

        /** Called only from the explicit Connect button after local owner action. */
        fun startInternetSession(context: Context, peer: SecureInternetPeer) {
            pendingInternetPeer?.close()
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

        /** Stop the Internet transport without revoking a separately active LAN session. */
        fun stopInternetSession(context: Context) {
            context.applicationContext.startService(
                Intent(context, SessionForegroundService::class.java).setAction(ACTION_STOP_INTERNET)
            )
        }
    }
}
