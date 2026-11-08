package com.rkremote.remote

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap

/**
 * Bounded H.264 access-unit transport over RemoteMessage envelopes.
 *
 * Each JSON chunk is deliberately small enough to remain below the 64 KiB
 * protocol envelope limit after Base64 and JSON overhead. The caller must send
 * the resulting messages only over an already authenticated SecureInternetPeer.
 */
class SecureVideoFrameCodec(
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
    private val chunkBytes: Int = CHUNK_BYTES,
    private val maxAssemblies: Int = MAX_ASSEMBLIES,
    private val assemblyTimeoutMs: Long = ASSEMBLY_TIMEOUT_MS
) {
    data class EncodedFrame(
        val streamId: String,
        val frameSequence: Long,
        val presentationTimeUs: Long,
        val flags: Int,
        val width: Int,
        val height: Int,
        val bytes: ByteArray
    )

    private data class Assembly(
        val streamId: String,
        val frameSequence: Long,
        val presentationTimeUs: Long,
        val flags: Int,
        val width: Int,
        val height: Int,
        val totalBytes: Int,
        val chunkCount: Int,
        val createdAtMs: Long,
        val chunks: Array<ByteArray?>,
        var receivedBytes: Int = 0,
        var receivedChunks: Int = 0
    )

    private val assemblies = LinkedHashMap<String, Assembly>(maxAssemblies, 0.75f, true)
    private var lastCompletedSequence = -1L

    init {
        require(maxFrameBytes in 1..MAX_FRAME_BYTES)
        require(chunkBytes in 1024..MAX_CHUNK_BYTES)
        require(maxAssemblies in 1..MAX_ASSEMBLIES)
        require(assemblyTimeoutMs in 1..ASSEMBLY_TIMEOUT_MS)
    }

    fun encode(frame: EncodedFrame): List<JSONObject> {
        require(frame.streamId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(frame.frameSequence >= 0)
        require(frame.presentationTimeUs >= 0)
        require(frame.width in 2..480 && frame.height in 2..480)
        require(frame.bytes.isNotEmpty() && frame.bytes.size <= maxFrameBytes)
        val count = (frame.bytes.size + chunkBytes - 1) / chunkBytes
        require(count <= MAX_CHUNKS)
        return (0 until count).map { index ->
            val start = index * chunkBytes
            val end = minOf(frame.bytes.size, start + chunkBytes)
            val chunk = frame.bytes.copyOfRange(start, end)
            JSONObject()
                .put("streamId", frame.streamId)
                .put("frameSequence", frame.frameSequence)
                .put("presentationTimeUs", frame.presentationTimeUs)
                .put("flags", frame.flags)
                .put("width", frame.width)
                .put("height", frame.height)
                .put("totalBytes", frame.bytes.size)
                .put("chunkIndex", index)
                .put("chunkCount", count)
                .put("data", Base64.encodeToString(chunk, Base64.NO_WRAP or Base64.URL_SAFE))
        }
    }

    @Synchronized
    fun accept(payload: JSONObject, nowMillis: Long = System.currentTimeMillis()): EncodedFrame? {
        expire(nowMillis)
        val streamId = payload.getString("streamId")
        require(streamId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val sequence = payload.getLong("frameSequence")
        val pts = payload.getLong("presentationTimeUs")
        val flags = payload.getInt("flags")
        val width = payload.getInt("width")
        val height = payload.getInt("height")
        val totalBytes = payload.getInt("totalBytes")
        val index = payload.getInt("chunkIndex")
        val count = payload.getInt("chunkCount")
        require(sequence >= 0 && pts >= 0)
        require(width in 2..480 && height in 2..480)
        require(totalBytes in 1..maxFrameBytes)
        require(count in 1..MAX_CHUNKS && index in 0 until count)
        require(count == (totalBytes + chunkBytes - 1) / chunkBytes) {
            "Inconsistent frame chunk count"
        }
        val key = "$streamId:$sequence"
        require(sequence > lastCompletedSequence || assemblies.containsKey(key)) {
            "Stale or replayed video frame"
        }
        val decoded = Base64.decode(payload.getString("data"), Base64.NO_WRAP or Base64.URL_SAFE)
        val expectedChunkSize = if (index == count - 1) totalBytes - index * chunkBytes else chunkBytes
        require(decoded.size == expectedChunkSize) { "Invalid video chunk length" }

        val assembly = assemblies[key] ?: run {
            require(assemblies.size < maxAssemblies) { "Too many incomplete video frames" }
            Assembly(
                streamId, sequence, pts, flags, width, height, totalBytes, count,
                nowMillis, arrayOfNulls(count)
            ).also { assemblies[key] = it }
        }
        require(
            assembly.presentationTimeUs == pts && assembly.flags == flags &&
                assembly.width == width && assembly.height == height &&
                assembly.totalBytes == totalBytes && assembly.chunkCount == count
        ) { "Video chunk metadata changed during assembly" }
        require(assembly.chunks[index] == null) { "Duplicate video chunk" }
        assembly.chunks[index] = decoded
        assembly.receivedBytes += decoded.size
        assembly.receivedChunks++
        require(assembly.receivedBytes <= totalBytes)
        if (assembly.receivedChunks != count) return null
        require(assembly.receivedBytes == totalBytes) { "Video frame size mismatch" }

        val output = ByteArrayOutputStream(totalBytes)
        assembly.chunks.forEach { part -> output.write(part ?: error("Missing video chunk")) }
        assemblies.remove(key)
        lastCompletedSequence = maxOf(lastCompletedSequence, sequence)
        return EncodedFrame(streamId, sequence, pts, flags, width, height, output.toByteArray())
    }

    @Synchronized
    fun clear() {
        assemblies.clear()
        lastCompletedSequence = -1L
    }

    private fun expire(nowMillis: Long) {
        val iterator = assemblies.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMillis - iterator.next().value.createdAtMs > assemblyTimeoutMs) iterator.remove()
        }
    }

    companion object {
        const val MAX_FRAME_BYTES = 2 * 1024 * 1024
        const val CHUNK_BYTES = 32 * 1024
        const val MAX_CHUNK_BYTES = 48 * 1024
        const val MAX_CHUNKS = 64
        const val MAX_ASSEMBLIES = 2
        const val ASSEMBLY_TIMEOUT_MS = 5_000L
    }
}
