package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rkremote.MainActivity
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Target-side PTT speaker. The owner must start this service locally.
 * Incoming audio is bounded, short-lived PCM data; no audio is stored.
 */
object PttAudioQueue {
    private const val MAX_QUEUED_CHUNKS = 8
    private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_CHUNKS)
    private val enabled = AtomicBoolean(false)

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) queue.clear()
    }

    fun offer(chunk: ByteArray): Boolean {
        if (!enabled.get() || chunk.isEmpty() || chunk.size > MAX_CHUNK_BYTES || chunk.size % 2 != 0) return false
        // Keep latency bounded: discard the oldest chunk when the queue is full.
        if (!queue.offer(chunk.copyOf())) {
            queue.poll()
            return queue.offer(chunk.copyOf())
        }
        return true
    }

    fun poll(timeoutMs: Long): ByteArray? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    fun clear() = queue.clear()
    fun isEnabled(): Boolean = enabled.get()

    const val MAX_CHUNK_BYTES = 4096
    const val MAX_QUEUED_CHUNKS_FOR_STATUS = 8
}

/** Visible owner-enabled playback service with a persistent stop action. */
class PttPlaybackService : Service() {
    private val active = AtomicBoolean(false)
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var track: AudioTrack? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "RKRemote push-to-talk", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (active.get()) return START_NOT_STICKY
        return try {
            startAsForeground()
            startPlayback()
            START_NOT_STICKY
        } catch (_: Exception) {
            stopPlayback()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startAsForeground() {
        val stop = PendingIntent.getService(
            this, 101, Intent(this, PttPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 102, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("RKRemote push-to-talk is enabled")
            .setContentText("This phone can play audio sent by its paired controller.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Disable PTT speaker", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startPlayback() {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "16 kHz PCM playback is not supported" }
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        require(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "Unable to initialize PTT speaker" }
        track = audioTrack
        active.set(true)
        PttAudioQueue.setEnabled(true)
        audioTrack.play()
        playbackThread = Thread({
            try {
                while (active.get()) {
                    val chunk = PttAudioQueue.poll(200) ?: continue
                    var offset = 0
                    while (active.get() && offset < chunk.size) {
                        val written = audioTrack.write(
                            chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) break
                        offset += written
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                stopSelf()
            }
        }, "RKRemote-PTT-Playback").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopPlayback() {
        active.set(false)
        PttAudioQueue.setEnabled(false)
        PttAudioQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null
        val old = track
        track = null
        runCatching { old?.pause() }
        runCatching { old?.flush() }
        runCatching { old?.stop() }
        runCatching { old?.release() }
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.rkremote.action.STOP_PTT_PLAYBACK"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = "rkremote_ptt_playback"
        private const val NOTIFICATION_ID = 4205
    }
}
