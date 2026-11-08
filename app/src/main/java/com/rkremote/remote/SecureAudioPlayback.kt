package com.rkremote.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded controller-side playback for owner-approved secure microphone monitoring. */
class SecureAudioPlayback : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(12)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (running.get()) return
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minimum > 0) { "16 kHz PCM playback is not supported" }
        val created = AudioTrack.Builder()
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
            .setBufferSizeInBytes(maxOf(minimum * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(created.state == AudioTrack.STATE_INITIALIZED) {
            created.release()
            "Unable to initialize secure audio playback"
        }
        track = created
        running.set(true)
        created.play()
        worker = Thread({
            try {
                while (running.get()) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    var offset = 0
                    while (running.get() && offset < chunk.size) {
                        val written = created.write(
                            chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) break
                        offset += written
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                close()
            }
        }, "RKRemote-Secure-Audio").apply {
            isDaemon = true
            start()
        }
    }

    fun offer(bytes: ByteArray): Boolean {
        if (!running.get() || bytes.isEmpty() || bytes.size > MAX_CHUNK_BYTES || bytes.size % 2 != 0) return false
        if (queue.offer(bytes.copyOf())) return true
        queue.poll()
        return queue.offer(bytes.copyOf())
    }

    @Synchronized
    override fun close() {
        if (!running.getAndSet(false)) {
            queue.clear()
            return
        }
        queue.clear()
        worker?.interrupt()
        worker = null
        val old = track
        track = null
        runCatching { old?.pause() }
        runCatching { old?.flush() }
        runCatching { old?.stop() }
        runCatching { old?.release() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_CHUNK_BYTES = 4096
    }
}
