package com.rkremote.remote

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Controller-side H.264 decoder. All codec work is serialized off the UI thread. */
class SecureScreenVideoRenderer(
    private val surfaceView: SurfaceView,
    private val onError: (String) -> Unit = {}
) : SurfaceHolder.Callback, AutoCloseable {
    private data class Config(
        val width: Int,
        val height: Int,
        val csd0: ByteArray,
        val csd1: ByteArray
    )

    private val thread = HandlerThread("RKRemote-Secure-H264-Decoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private var surface: Surface? = null
    private var config: Config? = null
    private var decoder: MediaCodec? = null
    private var activeStreamId: String? = null
    private val frameCodec = SecureVideoFrameCodec()

    init {
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        handler.post {
            surface = holder.surface
            configureDecoderIfReady()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        handler.post {
            surface = null
            releaseDecoder()
        }
    }

    fun accept(message: RemoteMessage) {
        if (closed.get()) return
        when (message.type) {
            "screen.video.config" -> {
                val payload = message.payload
                val width = payload.getInt("width")
                val height = payload.getInt("height")
                require(width in 2..480 && height in 2..480)
                val csd0 = Base64.decode(payload.getString("csd0"), Base64.DEFAULT)
                val csd1 = Base64.decode(payload.getString("csd1"), Base64.DEFAULT)
                require(csd0.isNotEmpty() && csd0.size <= MAX_CODEC_CONFIG_BYTES && csd1.size <= MAX_CODEC_CONFIG_BYTES)
                val streamId = payload.getString("streamId")
                require(streamId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                handler.post {
                    activeStreamId = streamId
                    config = Config(width, height, csd0, csd1)
                    frameCodec.clear()
                    releaseDecoder()
                    configureDecoderIfReady()
                }
            }
            "screen.video.chunk" -> {
                handler.post {
                    val completed = frameCodec.accept(message.payload) ?: return@post
                    if (completed.streamId != activeStreamId) return@post
                    render(completed)
                }
            }
            "screen.video.end" -> {
                val streamId = message.payload.optString("streamId")
                handler.post {
                    if (streamId == activeStreamId) {
                        activeStreamId = null
                        config = null
                        frameCodec.clear()
                        releaseDecoder()
                    }
                }
            }
        }
    }

    private fun configureDecoderIfReady() {
        if (closed.get() || decoder != null) return
        val currentConfig = config ?: return
        val currentSurface = surface ?: return
        if (!currentSurface.isValid) return
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                currentConfig.width,
                currentConfig.height
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(currentConfig.csd0))
                if (currentConfig.csd1.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(currentConfig.csd1))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SecureVideoFrameCodec.MAX_FRAME_BYTES)
            }
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(format, currentSurface, null, 0)
                it.start()
            }
        } catch (error: Exception) {
            releaseDecoder()
            onError(error.message ?: "Could not initialize H.264 decoder")
        }
    }

    private fun render(frame: SecureVideoFrameCodec.EncodedFrame) {
        val codec = decoder ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) return
            val input = codec.getInputBuffer(inputIndex) ?: return
            if (frame.bytes.size > input.capacity()) {
                codec.queueInputBuffer(inputIndex, 0, 0, frame.presentationTimeUs, 0)
                return
            }
            input.clear()
            input.put(frame.bytes)
            codec.queueInputBuffer(
                inputIndex,
                0,
                frame.bytes.size,
                frame.presentationTimeUs,
                frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
            )
            val info = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
                outputIndex = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (error: Exception) {
            onError(error.message ?: "H.264 frame decode failed")
            releaseDecoder()
        }
    }

    private fun releaseDecoder() {
        val old = decoder ?: return
        decoder = null
        runCatching { old.stop() }
        runCatching { old.release() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        surfaceView.holder.removeCallback(this)
        handler.post {
            frameCodec.clear()
            releaseDecoder()
            surface = null
            thread.quitSafely()
        }
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val MAX_CODEC_CONFIG_BYTES = 8 * 1024
    }
}
