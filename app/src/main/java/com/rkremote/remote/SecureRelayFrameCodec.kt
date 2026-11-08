package com.rkremote.remote

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compact framing for the Cloudflare Noise relay.
 *
 * Each logical application message is optionally DEFLATE-compressed (only when
 * the result is meaningfully smaller), prefixed with a bounded envelope,
 * fragmented below the Worker's 12 KiB binary-frame ceiling, then each fragment
 * is independently protected by the established Noise transport cipher.
 */
internal class SecureRelayFrameCodec(
    private val maxCiphertextFrameBytes: Int = SecureLinkHandshake.MAX_FRAME_BYTES,
    private val maxMessageBytes: Int = RemoteMessage.MAX_MESSAGE_BYTES
) {
    private val maxNoisePayloadBytes = maxCiphertextFrameBytes - NOISE_OVERHEAD_BYTES
    private val framer = SecureDataChannelFramer(
        maxFrameBytes = maxNoisePayloadBytes,
        maxMessageBytes = maxMessageBytes + ENVELOPE_BYTES
    )

    init {
        require(maxNoisePayloadBytes > HEADER_BYTES + 1)
        require(maxMessageBytes > 0)
    }

    @Synchronized
    fun encode(message: ByteArray): List<ByteArray> {
        require(message.isNotEmpty() && message.size <= maxMessageBytes) {
            "Application message size is outside the allowed range"
        }

        val compressed = if (message.size >= MIN_COMPRESS_BYTES) deflate(message) else null
        val useCompressed = compressed != null &&
            compressed.size + MIN_COMPRESSION_SAVING < message.size
        val body = if (useCompressed) compressed!! else message
        val envelope = ByteBuffer.allocate(ENVELOPE_BYTES + body.size)
            .putInt(MAGIC)
            .put(if (useCompressed) FLAG_DEFLATE else 0)
            .putInt(message.size)
            .put(body)
            .array()
        return framer.fragment(envelope)
    }

    @Synchronized
    fun accept(fragment: ByteArray): ByteArray? {
        val envelope = framer.accept(fragment) ?: return null
        require(envelope.size >= ENVELOPE_BYTES) { "Relay envelope is truncated" }
        val input = ByteBuffer.wrap(envelope)
        require(input.int == MAGIC) { "Unknown relay envelope format" }
        val flags = input.get().toInt() and 0xff
        require(flags and FLAG_DEFLATE.inv() == 0) { "Unknown relay envelope flags" }
        val expectedLength = input.int
        require(expectedLength in 1..maxMessageBytes) { "Invalid original message length" }
        val body = ByteArray(input.remaining()).also(input::get)
        val result = if (flags and FLAG_DEFLATE != 0) {
            inflate(body, expectedLength)
        } else {
            require(body.size == expectedLength) { "Uncompressed relay length mismatch" }
            body
        }
        require(result.size == expectedLength) { "Decoded relay length mismatch" }
        return result
    }

    @Synchronized
    fun clear() = framer.clear()

    private fun deflate(input: ByteArray): ByteArray? {
        val deflater = Deflater(Deflater.BEST_SPEED)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0) return null
                output.write(buffer, 0, count)
                if (output.size() >= input.size) return null
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray, expectedLength: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val output = ByteArray(expectedLength + 1)
            var offset = 0
            while (!inflater.finished() && offset < output.size) {
                val count = inflater.inflate(output, offset, output.size - offset)
                if (count == 0) {
                    require(!inflater.needsDictionary() && !inflater.needsInput()) {
                        "Compressed relay payload is incomplete or requires a dictionary"
                    }
                }
                offset += count
            }
            require(inflater.finished() && offset == expectedLength && inflater.remaining == 0) {
                "Compressed relay payload length or terminator is invalid"
            }
            output.copyOf(expectedLength)
        } catch (error: java.util.zip.DataFormatException) {
            throw IllegalArgumentException("Compressed relay payload is malformed", error)
        } finally {
            inflater.end()
        }
    }

    companion object {
        private const val MAGIC = 0x524B4331 // RKC1
        private const val FLAG_DEFLATE = 0x01
        private const val ENVELOPE_BYTES = 9
        private const val NOISE_OVERHEAD_BYTES = 1 + 16 // APP marker + ChaChaPoly tag
        private const val MIN_COMPRESS_BYTES = 512
        private const val MIN_COMPRESSION_SAVING = 32
    }
}
