package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rkremote.MainActivity
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owner-approved H.264 screen capture. Encoded access units are kept in a
 * latest-frame slot; slow viewers never build an unbounded frame backlog.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var worker: HandlerThread? = null
    @Volatile private var drainWorker: Thread? = null
    private val active = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "RKRemote screen sharing", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (_: Exception) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code != RESULT_OK || data == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        stopCapture()
        return try {
            beginCapture(code, data)
            START_NOT_STICKY
        } catch (error: Exception) {
            ScreenVideoStore.clear()
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startAsForeground() {
        val stop = PendingIntent.getService(
            this, 71, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 72, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("RKRemote screen sharing is active")
            .setContentText("Live screen video is being shared with an approved RKRemote controller.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop sharing", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)
        projection = mediaProjection
        val metrics = resources.displayMetrics
        val sourceWidth = metrics.widthPixels.coerceAtLeast(2)
        val sourceHeight = metrics.heightPixels.coerceAtLeast(2)
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(sourceWidth, sourceHeight))
        val width = ((sourceWidth * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val height = ((sourceHeight * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder = codec
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        val thread = HandlerThread("RKRemote-H264").apply { start() }
        worker = thread
        val handler = Handler(thread.looper)
        active.set(true)
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === mediaProjection) {
                    stopCapture()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }, handler)
        display = mediaProjection.createVirtualDisplay(
            "RKRemote-live-video", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, handler
        )
        startCodecDrain(codec)
    }

    private fun startCodecDrain(codec: MediaCodec) {
        val drain = Thread({
            val info = MediaCodec.BufferInfo()
            while (active.get()) {
                try {
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            val csd0 = outputFormat.getByteBuffer("csd-0")?.copyRemaining() ?: ByteArray(0)
                            val csd1 = outputFormat.getByteBuffer("csd-1")?.copyRemaining() ?: ByteArray(0)
                            ScreenVideoStore.configure(
                                outputFormat.getInteger(MediaFormat.KEY_WIDTH),
                                outputFormat.getInteger(MediaFormat.KEY_HEIGHT), csd0, csd1
                            )
                        }
                        else -> if (index >= 0) {
                            val outputBuffer = codec.getOutputBuffer(index)
                            if (outputBuffer != null && info.size > 0 &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val duplicate = outputBuffer.duplicate()
                                duplicate.position(info.offset)
                                duplicate.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                duplicate.get(bytes)
                                ScreenVideoStore.publish(info.presentationTimeUs, info.flags, bytes)
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                } catch (_: IllegalStateException) {
                    // MediaCodec can enter an invalid state independently of an
                    // owner stop request. Do not leave a live foreground service
                    // advertising capture while no frames can be produced.
                    if (active.compareAndSet(true, false)) {
                        ScreenVideoStore.clear()
                        stopSelf()
                    }
                    break
                } catch (_: Exception) {
                    // An encoder failure is terminal for this capture session. Avoid
                    // a hot retry loop that can burn CPU while the codec is unhealthy.
                    active.set(false)
                    ScreenVideoStore.clear()
                    stopSelf()
                    break
                }
            }
        }, "RKRemote-H264-Drain").apply {
            isDaemon = true
            drainWorker = this
            start()
        }
    }

    private fun ByteBuffer.copyRemaining(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun stopCapture() {
        active.set(false)
        runCatching { display?.release() }
        display = null
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }
        val oldEncoder = encoder
        encoder = null
        runCatching { oldEncoder?.signalEndOfInputStream() }
        val oldDrainWorker = drainWorker
        if (oldDrainWorker != null && oldDrainWorker !== Thread.currentThread()) {
            runCatching { oldDrainWorker.join(750) }
        }
        oldDrainWorker?.interrupt()
        drainWorker = null
        runCatching { oldEncoder?.stop() }
        runCatching { oldEncoder?.release() }
        worker?.quitSafely()
        worker = null
        ScreenVideoStore.clear()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.rkremote.action.STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val RESULT_OK = -1
        private const val CHANNEL = "rkremote_screen_share"
        private const val NOTIFICATION_ID = 4202
        private const val MAX_DIMENSION = 480
        private const val TARGET_FPS = 10
        private const val BIT_RATE = 350_000
    }
}
