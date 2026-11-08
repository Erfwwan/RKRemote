package com.rkremote.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject

class SecureVideoFrameCodecTest {
    @Test
    fun fragmentsAndReassemblesLargeH264AccessUnitOutOfOrder() {
        val codec = SecureVideoFrameCodec()
        val original = ByteArray(180_000) { (it % 251).toByte() }
        val chunks = codec.encode(
            SecureVideoFrameCodec.EncodedFrame(
                streamId = "screen-1",
                frameSequence = 1,
                presentationTimeUs = 33_333,
                flags = 0,
                width = 480,
                height = 270,
                bytes = original
            )
        )
        assertEquals(6, chunks.size)
        var decoded: SecureVideoFrameCodec.EncodedFrame? = null
        chunks.reversed().forEach { decoded = codec.accept(it) ?: decoded }
        assertArrayEquals(original, decoded!!.bytes)
        assertEquals(1L, decoded!!.frameSequence)
        assertEquals(480, decoded!!.width)
    }

    @Test
    fun rejectsDuplicateChunks() {
        val codec = SecureVideoFrameCodec()
        val chunks = codec.encode(frame(ByteArray(70_000) { 7 }))
        assertNull(codec.accept(chunks[0]))
        assertThrows(IllegalArgumentException::class.java) { codec.accept(chunks[0]) }
    }

    @Test
    fun rejectsChangedMetadataAndMalformedChunkSize() {
        val codec = SecureVideoFrameCodec()
        val chunks = codec.encode(frame(ByteArray(70_000) { 3 }))
        codec.accept(chunks[0])
        val changed = JSONObject(chunks[1].toString()).put("width", 320)
        assertThrows(IllegalArgumentException::class.java) { codec.accept(changed) }

        val other = SecureVideoFrameCodec()
        val malformed = JSONObject(chunks[0].toString()).put("data", "AA")
        assertThrows(IllegalArgumentException::class.java) { other.accept(malformed) }
    }

    @Test
    fun rejectsOversizedFrameAndInvalidDimensions() {
        val codec = SecureVideoFrameCodec()
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(frame(ByteArray(SecureVideoFrameCodec.MAX_FRAME_BYTES + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(frame(ByteArray(10), width = 900))
        }
    }

    private fun frame(bytes: ByteArray, width: Int = 480) =
        SecureVideoFrameCodec.EncodedFrame(
            streamId = "screen-1",
            frameSequence = 1,
            presentationTimeUs = 1,
            flags = 0,
            width = width,
            height = 270,
            bytes = bytes
        )
}
