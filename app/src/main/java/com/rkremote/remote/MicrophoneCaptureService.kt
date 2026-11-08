package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rkremote.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A single short PCM chunk for the active controller; never written to disk. */
object MicrophoneChunkStore {
    private val latest = AtomicReference<ByteArray?>(null)
    @Volatile private var capturing = false
    @Synchronized
    fun publish(bytes: ByteArray) { if (capturing) latest.set(bytes.copyOf()) }
    @Synchronized
    fun take(): ByteArray? = latest.getAndSet(null)
    @Synchronized
    fun setCapturing(value: Boolean) { capturing = value; if (!value) latest.set(null) }
    fun isCapturing(): Boolean = capturing
    @Synchronized
    fun clear() { latest.set(null); capturing = false }
}

/**
 * Captures mono 16 kHz PCM only after a visible user action and RECORD_AUDIO
 * permission. The foreground notification exposes an immediate stop action.
 */
class MicrophoneCaptureService : Service() {
    private val active = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            MicrophoneChunkStore.clear()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
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
        // Keep the normal microphone route; optional effects attach to this recording session.
        // Hardware effects are optional and may be unavailable on some devices.
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(audio.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        active.set(true)
        audio.startRecording()
        MicrophoneChunkStore.setCapturing(true)
        val thread = Thread({
            val buffer = ByteArray(4096)
            try {
                while (active.get()) {
                    val count = try {
                        audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    } catch (_: Exception) {
                        AudioRecord.ERROR_INVALID_OPERATION
                    }
                    if (count > 0 && active.get()) {
                        MicrophoneChunkStore.publish(buffer.copyOf(count))
                    } else if (count < 0) {
                        break
                    }
                }
            } finally {
                // An unexpected recorder error must not leave a foreground service
                // and a stale "capturing" flag running without an audio source.
                if (active.compareAndSet(true, false)) {
                    MicrophoneChunkStore.clear()
                    stopSelf()
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
        // stop() unblocks a blocking AudioRecord.read(). Join before releasing
        // the recorder/effects so the capture thread cannot race native teardown.
        runCatching { old?.stop() }
        val captureWorker = worker
        if (captureWorker != null && captureWorker !== Thread.currentThread()) {
            runCatching { captureWorker.join(500) }
        }
        captureWorker?.interrupt()
        runCatching { old?.release() }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
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
