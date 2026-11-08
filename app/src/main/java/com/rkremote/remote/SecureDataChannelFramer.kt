package com.rkremote.remote

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.LinkedHashMap

/**
 * Bounded fragmentation/reassembly for ordered Noise-encrypted Cloudflare relay messages.
 * The Noise transport protects each fragment; this layer validates large-message framing.
 */
internal class SecureDataChannelFramer(
    private val maxFrameBytes: Int = 12 * 1024,
    private val maxMessageBytes: Int = RemoteMessage.MAX_MESSAGE_BYTES,
    private val maxInFlightMessages: Int = 4,
    private val maxAgeMillis: Long = 30_000L
) {
    private data class Assembly(
        val totalBytes: Int,
        val fragmentCount: Int,
        val fragments: Array<ByteArray?>,
        var receivedBytes: Int = 0,
        var receivedCount: Int = 0,
        val createdAt: Long
    )

    private val random = SecureRandom()
    private val assemblies = LinkedHashMap<Long, Assembly>()

    init {
        require(maxFrameBytes > HEADER_BYTES)
        require(maxMessageBytes > 0 && maxInFlightMessages > 0 && maxAgeMillis > 0)
    }

    @Synchronized
    fun fragment(message: ByteArray): List<ByteArray> {
        require(message.isNotEmpty() && message.size <= maxMessageBytes) {
            "Application message size is outside the allowed range"
        }
        val chunkCapacity = maxFrameBytes - HEADER_BYTES
        val count = (message.size + chunkCapacity - 1) / chunkCapacity
        require(count <= MAX_FRAGMENTS) { "Application message requires too many fragments" }
        var id: Long
        do {
            id = random.nextLong()
        } while (id == 0L || assemblies.containsKey(id))

        return (0 until count).map { index ->
            val offset = index * chunkCapacity
            val length = minOf(chunkCapacity, message.size - offset)
            ByteBuffer.allocate(HEADER_BYTES + length)
                .putInt(MAGIC)
                .putLong(id)
                .putInt(index)
                .putInt(count)
                .putInt(message.size)
                .put(message, offset, length)
                .array()
        }
    }

    @Synchronized
    fun accept(frame: ByteArray, nowMillis: Long = System.currentTimeMillis()): ByteArray? {
        expire(nowMillis)
        require(frame.size in (HEADER_BYTES + 1)..maxFrameBytes) { "Invalid DataChannel fragment size" }
        val input = ByteBuffer.wrap(frame)
        require(input.int == MAGIC) { "Unknown DataChannel frame format" }
        val id = input.long
        val index = input.int
        val count = input.int
        val total = input.int
        require(id != 0L && count in 1..MAX_FRAGMENTS && index in 0 until count) {
            "Invalid DataChannel fragment index"
        }
        require(total in 1..maxMessageBytes) { "Invalid DataChannel message length" }
        val chunk = ByteArray(input.remaining()).also(input::get)
        require(chunk.size <= maxFrameBytes - HEADER_BYTES) { "DataChannel fragment is too large" }

        val expectedCount = (total + (maxFrameBytes - HEADER_BYTES) - 1) / (maxFrameBytes - HEADER_BYTES)
        require(count == expectedCount) { "DataChannel fragment count does not match message length" }
        val chunkCapacity = maxFrameBytes - HEADER_BYTES
        val expectedChunkSize = if (index < count - 1) {
            chunkCapacity
        } else {
            total - chunkCapacity * (count - 1)
        }
        require(chunk.size == expectedChunkSize) {
            "DataChannel fragment length does not match its index"
        }

        val assembly = assemblies[id] ?: run {
            require(assemblies.size < maxInFlightMessages) { "Too many incomplete DataChannel messages" }
            Assembly(total, count, arrayOfNulls(count), createdAt = nowMillis)
                .also { assemblies[id] = it }
        }
        require(assembly.totalBytes == total && assembly.fragmentCount == count) {
            "Conflicting DataChannel fragment metadata"
        }
        require(assembly.fragments[index] == null) { "Duplicate DataChannel fragment" }
        val remainingExpected = total - assembly.receivedBytes
        require(chunk.size <= remainingExpected) { "DataChannel fragments exceed declared message length" }
        assembly.fragments[index] = chunk
        assembly.receivedBytes += chunk.size
        assembly.receivedCount++

        if (assembly.receivedCount != count) return null
        require(assembly.receivedBytes == total) { "Reassembled DataChannel length mismatch" }
        val output = ByteArrayOutputStream(total)
        assembly.fragments.forEach { part ->
            output.write(part ?: error("Missing DataChannel fragment"))
        }
        assemblies.remove(id)
        return output.toByteArray()
    }

    @Synchronized
    fun clear() = assemblies.clear()

    private fun expire(nowMillis: Long) {
        val iterator = assemblies.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (nowMillis - item.value.createdAt > maxAgeMillis) iterator.remove()
        }
    }

    companion object {
        private const val HEADER_BYTES = 24
        private const val MAGIC = 0x524B4431 // RKD1
        private const val MAX_FRAGMENTS = 512
    }
}
