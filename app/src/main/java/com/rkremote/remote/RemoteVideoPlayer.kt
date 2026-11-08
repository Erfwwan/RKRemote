package com.rkremote.remote

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to the target's persistent RKV1/H.264 stream and decodes directly
 * to the supplied Surface. Reconnects with bounded backoff after link loss.
 */
class RemoteVideoPlayer(
    private val ip: String,
    private val token: String,
    private val surface: Surface,
    private val onStatus: (String) -> Unit = {},
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> }
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            var failures = 0
            while (running.get()) {
                try {
                    streamOnce()
                    failures = 0
                } catch (error: Exception) {
                    if (!running.get()) break
                    failures++
                    onStatus("Video link interrupted · reconnecting ($failures): ${error.message}")
                    try { Thread.sleep(backoff(failures)) } catch (_: InterruptedException) { break }
                }
            }
            onStatus("Live video stopped.")
        }, "RKRemote-Video-Player").apply { isDaemon = true; start() }
    }

    private fun streamOnce() {
        val connection = Socket()
        socket = connection
        connection.tcpNoDelay = true
        connection.keepAlive = true
        connection.connect(InetSocketAddress(ip, LanAgentServer.DEFAULT_PORT), 5_000)
        connection.soTimeout = 15_000
        val request = "GET /screen/video HTTP/1.1\\r\\nHost: $ip\\r\\nAuthorization: Bearer $token\\r\\nConnection: close\\r\\n\\r\\n"
        connection.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        connection.getOutputStream().flush()
        val input = DataInputStream(BufferedInputStream(connection.getInputStream()))
        val status = readLine(input)
        if (!status.contains(" 200 ")) {
            val headers = readHeaders(input)
            throw IllegalStateException("$status ${headers.take(200)}")
        }
        readHeaders(input)
        val magic = ByteArray(4)
        input.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == "RKV1") { "Unknown video stream format" }
        val width = input.readInt()
        val height = input.readInt()
        require(width in 2..480 && height in 2..480) { "Video dimensions exceed the 480 px limit" }
        val csd0 = readConfig(input)
        val csd1 = readConfig(input)
        onVideoSize(width, height)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            if (csd1.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
        }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            decoder.configure(format, surface, null, 0)
            decoder.start()
            var waitingForKeyFrame = true
            while (running.get()) {
                val pts = input.readLong()
                val flags = input.readInt()
                val size = input.readInt()
                require(size in 1..ScreenVideoStore.MAX_ACCESS_UNIT) { "Invalid video access-unit size" }
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val keyFrame = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (!waitingForKeyFrame || keyFrame) {
                    waitingForKeyFrame = false
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: error("Decoder input buffer unavailable")
                        buffer.clear()
                        require(bytes.size <= buffer.remaining()) { "Video frame exceeds decoder input buffer" }
                        buffer.put(bytes)
                        decoder.queueInputBuffer(
                            inputIndex, 0, bytes.size, pts,
                            if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        )
                    }
                }
                val info = MediaCodec.BufferInfo()
                var outputIndex = decoder.dequeueOutputBuffer(info, 0)
                while (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, true)
                    outputIndex = decoder.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            runCatching { connection.close() }
            if (socket === connection) socket = null
        }
    }

    private fun readConfig(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..(64 * 1024)) { "Invalid codec configuration" }
        return ByteArray(size).also { input.readFully(it) }
    }

    private fun readHeaders(input: DataInputStream): String {
        val result = StringBuilder()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            result.append(line).append(' ')
            require(result.length <= 8192) { "Response headers too large" }
        }
        return result.toString()
    }

    private fun readLine(input: DataInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= 2048) {
            val value = input.read()
            if (value < 0) throw java.io.EOFException("Video stream closed")
            if (value == 10) break
            if (value != 13) bytes.write(value)
        }
        require(bytes.size() <= 2048) { "HTTP response line too long" }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        worker?.interrupt()
        worker = null
    }

    private fun backoff(attempt: Int): Long =
        when (attempt.coerceAtMost(5)) { 1 -> 500L; 2 -> 1_000L; 3 -> 2_000L; 4 -> 4_000L; else -> 8_000L }
}
