package com.rkremote.remote

import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams the owner's already-running MediaProjection encoder output over the
 * authenticated Noise-encrypted Cloudflare relay. This class never starts screen capture.
 */
class SecureScreenStreamSender(
    private val sendMessage: (String, JSONObject) -> Boolean,
    private val onState: (String) -> Unit = {}
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val streamId = java.util.UUID.randomUUID().toString().replace("-", "")

    @Synchronized
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        worker = Thread({ runStream() }, "RKRemote-Secure-Screen-Sender").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun runStream() {
        var lastSequence = 0L
        try {
            val config = ScreenVideoStore.awaitConfig(CONFIG_WAIT_MS)
                ?: error("Screen sharing is not active on the target device")
            require(config.width in 2..480 && config.height in 2..480)
            require(config.csd0.isNotEmpty() && config.csd0.size <= MAX_CODEC_CONFIG_BYTES && config.csd1.size <= MAX_CODEC_CONFIG_BYTES) {
                "H.264 codec configuration is too large"
            }
            val configPayload = JSONObject()
                .put("streamId", streamId)
                .put("width", config.width)
                .put("height", config.height)
                .put("csd0", Base64.encodeToString(config.csd0, Base64.NO_WRAP))
                .put("csd1", Base64.encodeToString(config.csd1, Base64.NO_WRAP))
            check(sendMessage("screen.video.config", configPayload)) {
                "Could not send H.264 stream configuration"
            }
            onState("Secure screen stream started")
            val codec = SecureVideoFrameCodec()
            while (running.get()) {
                if (!ScreenVideoStore.hasConfig()) break
                val frame = ScreenVideoStore.awaitNext(lastSequence, FRAME_WAIT_MS) ?: continue
                lastSequence = frame.sequence
                val encoded = SecureVideoFrameCodec.EncodedFrame(
                    streamId = streamId,
                    frameSequence = frame.sequence,
                    presentationTimeUs = frame.ptsUs,
                    flags = frame.flags,
                    width = config.width,
                    height = config.height,
                    bytes = frame.bytes
                )
                val chunks = codec.encode(encoded)
                var complete = true
                for (chunk in chunks) {
                    if (!running.get() || !sendMessage("screen.video.chunk", chunk)) {
                        complete = false
                        break
                    }
                }
                if (!complete) {
                    // Ordered DataChannel may have received a partial access unit.
                    // Its bounded receiver assembly expires; skip this frame and
                    // resume at the next latest-only encoder output.
                    onState("Screen stream congested · skipped frame")
                }
            }
        } catch (error: Exception) {
            if (running.get()) onState(error.message ?: "Secure screen stream failed")
        } finally {
            running.set(false)
            runCatching {
                sendMessage("screen.video.end", JSONObject().put("streamId", streamId))
            }
            onState("Secure screen stream stopped")
        }
    }

    @Synchronized
    override fun close() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    companion object {
        private const val CONFIG_WAIT_MS = 10_000L
        private const val FRAME_WAIT_MS = 1_000L
        private const val MAX_CODEC_CONFIG_BYTES = 8 * 1024
    }
}
