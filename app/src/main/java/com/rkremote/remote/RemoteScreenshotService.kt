package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.rkremote.MainActivity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Separate owner-approved still-capture session. It keeps a native-resolution
 * ImageReader surface ready; no live video encoder or network stream is active.
 */
class RemoteScreenshotService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "RKRemote screenshot standby", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
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
            RemoteScreenshotStore.clear()
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startAsForeground() {
        val stop = PendingIntent.getService(
            this, 81, Intent(this, RemoteScreenshotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 82, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("RKRemote screenshot standby is active")
            .setContentText("The owner-approved still-capture session is ready for authenticated requests.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop standby", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun beginCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)
        projection = mediaProjection

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(2)
        val height = metrics.heightPixels.coerceAtLeast(2)

        val thread = HandlerThread("RKRemote-Still-Capture").apply { start() }
        worker = thread
        val handler = Handler(thread.looper)
        var densityDpi = metrics.densityDpi
        fun createReader(w: Int, h: Int): ImageReader =
            ImageReader.newInstance(w.coerceAtLeast(2), h.coerceAtLeast(2), PixelFormat.RGBA_8888, 2)
                .also { source ->
                    source.setOnImageAvailableListener({ available ->
                        runCatching { available.acquireLatestImage() }
                            .onSuccess { RemoteScreenshotStore.offer(it) }
                    }, handler)
                }
        val reader = createReader(width, height)
        imageReader = reader

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width < 2 || height < 2) return
                val currentDisplay = display ?: return
                val replacement = createReader(width, height)
                runCatching {
                    currentDisplay.resize(width, height, densityDpi)
                    currentDisplay.setSurface(replacement.surface)
                    val previous = imageReader
                    imageReader = replacement
                    previous?.setOnImageAvailableListener(null, null)
                    previous?.close()
                }.onFailure {
                    runCatching { replacement.close() }
                }
            }

            override fun onStop() {
                if (projection === mediaProjection) {
                    stopCapture()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }, handler)

        display = mediaProjection.createVirtualDisplay(
            "RKRemote-independent-screenshot", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
        )
    }

    private fun stopCapture() {
        runCatching { display?.release() }
        display = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }
        worker?.quitSafely()
        worker = null
        RemoteScreenshotStore.clear()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.rkremote.action.STOP_SCREENSHOT_STANDBY"
        const val EXTRA_RESULT_CODE = "screenshot_projection_result_code"
        const val EXTRA_RESULT_DATA = "screenshot_projection_result_data"
        private const val RESULT_OK = -1
        private const val CHANNEL = "rkremote_screenshot_standby"
        private const val NOTIFICATION_ID = 4203
    }
}

/** Single latest frame slot; image conversion/JPEG encoding happens only on request. */
internal object RemoteScreenshotStore {
    private val lock = java.lang.Object()
    private var latestImage: Image? = null

    fun offer(image: Image?) {
        if (image == null) return
        synchronized(lock) {
            runCatching { latestImage?.close() }
            latestImage = image
            lock.notifyAll()
        }
    }

    fun captureJpeg(timeoutMs: Long = 8_000L): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val image = synchronized(lock) {
            while (latestImage == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                try {
                    lock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            latestImage.also { latestImage = null }
        } ?: return null

        return image.use {
            val plane = it.planes.firstOrNull() ?: return null
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0 || rowStride <= 0) return null
            val paddedWidth = it.width + (rowStride - pixelStride * it.width) / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, it.height, Bitmap.Config.ARGB_8888)
            try {
                val buffer: ByteBuffer = plane.buffer
                buffer.rewind()
                padded.copyPixelsFromBuffer(buffer)
                val cropped = if (paddedWidth == it.width) padded else
                    Bitmap.createBitmap(padded, 0, 0, it.width, it.height)
                try {
                    ByteArrayOutputStream().use { output ->
                        check(cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                            "JPEG encoding failed"
                        }
                        output.toByteArray()
                    }
                } finally {
                    if (cropped !== padded) cropped.recycle()
                }
            } finally {
                padded.recycle()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching { latestImage?.close() }
            latestImage = null
            lock.notifyAll()
        }
    }

    private const val JPEG_QUALITY = 95
}
