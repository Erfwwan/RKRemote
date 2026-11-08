package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Size
import androidx.core.app.NotificationCompat
import com.rkremote.MainActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Latest owner-approved camera frame; kept in memory only. */
object CameraFrameStore {
    @Volatile var latestFrame: ByteArray? = null
        private set
    @Volatile var latestMimeType: String = "image/jpeg"
        private set
    val latestJpeg: ByteArray? get() = latestFrame
    fun publish(bytes: ByteArray, mimeType: String = "image/jpeg") {
        latestFrame = bytes
        latestMimeType = mimeType
    }
    fun clear() {
        latestFrame = null
        latestMimeType = "image/jpeg"
    }
}

/**
 * Camera capture starts only from a visible user action after CAMERA permission.
 * The foreground notification remains visible and offers a stop action.
 */
class CameraCaptureService : Service() {
    private val active = AtomicBoolean(false)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var activeLensFacing = CameraCharacteristics.LENS_FACING_BACK

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "RKRemote camera", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCamera()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val requestedFacing = intent?.getIntExtra(EXTRA_LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
            ?: CameraCharacteristics.LENS_FACING_BACK
        if (requestedFacing != CameraCharacteristics.LENS_FACING_BACK && requestedFacing != CameraCharacteristics.LENS_FACING_FRONT) return START_NOT_STICKY
        if (active.get()) {
            if (activeLensFacing == requestedFacing) return START_NOT_STICKY
            stopCamera()
        }
        return try {
            startAsForeground()
            startCamera(requestedFacing)
            START_NOT_STICKY
        } catch (_: Exception) {
            stopCamera()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startAsForeground() {
        val stop = PendingIntent.getService(
            this, 81, Intent(this, CameraCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 82, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("RKRemote camera is active")
            .setContentText("Camera frames may be viewed by your paired local controller.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop camera", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCamera(lensFacing: Int) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lensFacing
        } ?: error(if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "Front camera is not available" else "Rear camera is not available")
        activeLensFacing = lensFacing
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera output configuration is unavailable")
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val size: Size = sizes.filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.minByOrNull {
                it.width.toLong() * it.height
            } ?: error("Camera does not support JPEG output")

        val captureThread = HandlerThread("RKRemote-Camera").apply { start() }
        thread = captureThread
        val handler = Handler(captureThread.looper)
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        reader = imageReader
        active.set(true)
        imageReader.setOnImageAvailableListener({ source ->
            if (!active.get()) return@setOnImageAvailableListener
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 500) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastFrameAt = now
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val (encoded, mimeType) = encodeLosslesslyWhenSmaller(bytes)
                CameraFrameStore.publish(encoded, mimeType)
            } finally {
                image.close()
            }
        }, handler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!active.get()) {
                    device.close()
                    return
                }
                camera = device
                try {
                    device.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (!active.get()) {
                                    configured.close()
                                    return
                                }
                                session = configured
                                try {
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                        val slowRange = ranges?.firstOrNull { it.upper <= 10 }
                                        if (slowRange != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, slowRange)
                                    }.build()
                                    configured.setRepeatingRequest(request, null, handler)
                                } catch (_: Exception) {
                                    stopSelf()
                                }
                            }
                            override fun onConfigureFailed(failed: CameraCaptureSession) {
                                failed.close()
                                stopSelf()
                            }
                        },
                        handler
                    )
                } catch (_: Exception) {
                    stopSelf()
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (camera === device) camera = null
                stopSelf()
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (camera === device) camera = null
                stopSelf()
            }
        }, handler)
    }

    private fun encodeLosslesslyWhenSmaller(jpeg: ByteArray): Pair<ByteArray, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return jpeg to "image/jpeg"
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg to "image/jpeg"
        return try {
            val output = ByteArrayOutputStream()
            val encoded = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)
            val webp = output.toByteArray()
            if (encoded && webp.isNotEmpty() && webp.size < jpeg.size) webp to "image/webp" else jpeg to "image/jpeg"
        } catch (_: Exception) {
            jpeg to "image/jpeg"
        } finally {
            bitmap.recycle()
        }
    }

    private fun stopCamera() {
        active.set(false)
        CameraFrameStore.clear()
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
        thread?.quitSafely()
        thread = null
    }

    override fun onDestroy() {
        stopCamera()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.rkremote.action.STOP_CAMERA"
        const val EXTRA_LENS_FACING = "camera_lens_facing"
        private const val CHANNEL = "rkremote_camera_capture"
        private const val NOTIFICATION_ID = 4203
    }
}
