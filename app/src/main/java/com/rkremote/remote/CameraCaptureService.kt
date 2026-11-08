package com.rkremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
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
import androidx.core.content.ContextCompat
import com.rkremote.MainActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** One owner-approved still photo, kept in memory until replaced or cleared. */
object CameraFrameStore {
    @Volatile var latestFrame: ByteArray? = null
        private set
    @Volatile var latestMimeType: String = "image/jpeg"
        private set
    @Volatile private var capturing = false

    @Synchronized
    fun setCapturing(value: Boolean) {
        capturing = value
        if (value) {
            latestFrame = null
            latestMimeType = "image/jpeg"
        }
    }

    @Synchronized
    fun publish(bytes: ByteArray, mimeType: String = "image/jpeg") {
        if (!capturing) return
        latestFrame = bytes
        latestMimeType = mimeType
        capturing = false
    }

    @Synchronized
    fun clear() {
        capturing = false
        latestFrame = null
        latestMimeType = "image/jpeg"
    }
}

/** Captures exactly one still image after a visible local action. */
class CameraCaptureService : Service() {
    private val active = AtomicBoolean(false)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    @Volatile private var cameraGeneration = 0
    @Volatile private var photoSaved = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "RKRemote photo capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture(clearPhoto = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val facing = intent?.getIntExtra(EXTRA_LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
            ?: CameraCharacteristics.LENS_FACING_BACK
        if (facing != CameraCharacteristics.LENS_FACING_BACK && facing != CameraCharacteristics.LENS_FACING_FRONT) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active.get()) return START_NOT_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return try {
            photoSaved = false
            CameraFrameStore.setCapturing(true)
            active.set(true)
            startAsForeground()
            openCameraAndCapture(facing)
            START_NOT_STICKY
        } catch (_: Exception) {
            stopCapture(clearPhoto = true)
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
            .setContentTitle("RKRemote is taking one photo")
            .setContentText("The camera stops automatically after one image.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Cancel", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun openCameraAndCapture(lensFacing: Int) {
        val generation = ++cameraGeneration
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lensFacing
        } ?: error("Requested camera is not available")
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera output configuration is unavailable")
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val size: Size = sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: error("Camera does not support JPEG output")
        val captureThread = HandlerThread("RKRemote-OneShotCamera").apply { start() }
        thread = captureThread
        val handler = Handler(captureThread.looper)
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            if (!active.get() || generation != cameraGeneration || photoSaved) return@setOnImageAvailableListener
            val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                CameraFrameStore.publish(compressForTransfer(bytes), "image/jpeg")
                photoSaved = true
                finishAfterPhoto(generation)
            } catch (_: Exception) {
                stopCapture(clearPhoto = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                image.close()
            }
        }, handler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!active.get() || generation != cameraGeneration) {
                    device.close()
                    return
                }
                camera = device
                try {
                    device.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (!active.get() || generation != cameraGeneration) {
                                    configured.close()
                                    return
                                }
                                session = configured
                                try {
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.JPEG_QUALITY, 88.toByte())
                                    }.build()
                                    configured.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureFailed(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            failure: android.hardware.camera2.CaptureFailure
                                        ) {
                                            if (generation == cameraGeneration) {
                                                stopCapture(clearPhoto = true)
                                                stopForeground(STOP_FOREGROUND_REMOVE)
                                                stopSelf()
                                            }
                                        }
                                    }, handler)
                                } catch (_: Exception) {
                                    stopCapture(clearPhoto = true)
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                            }
                            override fun onConfigureFailed(failed: CameraCaptureSession) {
                                failed.close()
                                if (generation == cameraGeneration) {
                                    stopCapture(clearPhoto = true)
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                            }
                        },
                        handler
                    )
                } catch (_: Exception) {
                    stopCapture(clearPhoto = true)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (camera === device && active.get()) {
                    stopCapture(clearPhoto = true)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (camera === device && active.get()) {
                    stopCapture(clearPhoto = true)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }, handler)
    }

    private fun finishAfterPhoto(generation: Int) {
        if (generation != cameraGeneration) return
        stopCapture(clearPhoto = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun compressForTransfer(jpeg: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        return try {
            val output = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output) && output.size() > 0) output.toByteArray()
            else jpeg
        } catch (_: Exception) {
            jpeg
        } finally {
            bitmap.recycle()
        }
    }

    private fun stopCapture(clearPhoto: Boolean) {
        cameraGeneration += 1
        active.set(false)
        if (clearPhoto) CameraFrameStore.clear() else CameraFrameStore.setCapturing(false)
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
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
        stopCapture(clearPhoto = !photoSaved)
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
