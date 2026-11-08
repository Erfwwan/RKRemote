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
import androidx.core.app.NotificationCompat
import com.rkremote.MainActivity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory latest frame. Frames are never written to disk. */
object ScreenFrameStore {
    @Volatile var latestJpeg: ByteArray? = null
        private set

    fun publish(bytes: ByteArray) { latestJpeg = bytes }
    fun clear() { latestJpeg = null }
}

/**
 * Captures only after Android's MediaProjection consent dialog.
 * A persistent notification remains visible for the entire capture session.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    private val active = AtomicBoolean(false)
    @Volatile private var lastFrameAt = 0L

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

        startAsForeground()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (code != RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        stopCapture()
        return try {
            beginCapture(code, data)
            START_NOT_STICKY
        } catch (_: Exception) {
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
            .setContentText("Your screen is being shared on the local network.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop sharing", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)
        projection = mediaProjection
        val metrics = resources.displayMetrics
        val sourceWidth = metrics.widthPixels.coerceAtLeast(1)
        val sourceHeight = metrics.heightPixels.coerceAtLeast(1)
        // Limit snapshot resolution to keep CPU, memory, and LAN traffic modest.
        val scale = minOf(1f, MAX_CAPTURE_DIMENSION.toFloat() / maxOf(sourceWidth, sourceHeight))
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val density = metrics.densityDpi
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        val thread = HandlerThread("RKRemote-ScreenCapture").apply { start() }
        worker = thread
        val handler = Handler(thread.looper)
        active.set(true)
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // stopCapture() nulls projection before calling stop(), so its own
                // cleanup callback cannot recursively stop a newly-started session.
                if (projection === mediaProjection) {
                    stopCapture()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }, handler)
        imageReader.setOnImageAvailableListener({ source ->
            if (!active.get()) return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 700) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastFrameAt = now
            runCatching { ScreenFrameStore.publish(imageToJpeg(image)) }
            image.close()
        }, handler)
        display = mediaProjection.createVirtualDisplay(
            "RKRemote-screen", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler
        )
    }

    private fun imageToJpeg(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val output = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 55, output)
        padded.recycle()
        if (cropped !== padded) cropped.recycle()
        return output.toByteArray()
    }

    private fun stopCapture() {
        active.set(false)
        runCatching { display?.release() }
        display = null
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }
        worker?.quitSafely()
        worker = null
        ScreenFrameStore.clear()
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
        private const val MAX_CAPTURE_DIMENSION = 1600
    }
}
