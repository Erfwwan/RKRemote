package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rkremote.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A single short PCM chunk for the active controller; never written to disk. */
object MicrophoneChunkStore {
    private val latest = AtomicReference<ByteArray?>(null)
    fun publish(bytes: ByteArray) { latest.set(bytes) }
    fun take(): ByteArray? = latest.getAndSet(null)
    fun clear() { latest.set(null) }
}

/**
 * Captures mono 16 kHz PCM only after a visible user action and RECORD_AUDIO
 * permission. The foreground notification exposes an immediate stop action.
 */
class MicrophoneCaptureService : Service() {
    private val active = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "RKRemote microphone", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (active.get()) return START_NOT_STICKY
        return try {
            startAsForeground()
            startRecording()
            START_NOT_STICKY
        } catch (_: Exception) {
            stopRecording()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startAsForeground() {
        val stop = PendingIntent.getService(
            this, 91, Intent(this, MicrophoneCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 92, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("RKRemote microphone is active")
            .setContentText("Microphone audio is available to your paired local controller.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop microphone", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording() {
        val sampleRate = 16_000
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minimum > 0) { "Microphone audio format is not supported" }
        val bufferSize = maxOf(minimum * 2, 8192)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        require(audio.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize microphone" }
        recorder = audio
        active.set(true)
        audio.startRecording()
        val thread = Thread({
            val buffer = ByteArray(4096)
            while (active.get()) {
                val count = try {
                    audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                } catch (_: Exception) {
                    -1
                }
                if (count > 0 && active.get()) {
                    MicrophoneChunkStore.publish(buffer.copyOf(count))
                } else if (count < 0) {
                    break
                }
            }
        }, "RKRemote-Microphone").apply {
            isDaemon = true
            start()
        }
        worker = thread
    }

    private fun stopRecording() {
        active.set(false)
        MicrophoneChunkStore.clear()
        val old = recorder
        recorder = null
        runCatching { old?.stop() }
        runCatching { old?.release() }
        worker?.interrupt()
        worker = null
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.rkremote.action.STOP_MICROPHONE"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = "rkremote_microphone_capture"
        private const val NOTIFICATION_ID = 4204
    }
}
