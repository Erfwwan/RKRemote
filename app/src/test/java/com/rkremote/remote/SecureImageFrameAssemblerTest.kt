package com.rkremote.remote

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class SecureImageFrameAssemblerTest {
    @Test
    fun reassemblesOrderedCameraFrameAndVerifiesHash() {
        val source = ByteArray(70_000) { (it % 251).toByte() }
        val assembler = SecureImageFrameAssembler()
        assembler.begin(JSONObject()
            .put("transferId", "camera-1")
            .put("totalBytes", source.size)
            .put("mimeType", "image/jpeg"))
        var offset = 0
        while (offset < source.size) {
            val end = minOf(offset + SecureImageFrameAssembler.MAX_CHUNK_BYTES, source.size)
            val chunk = source.copyOfRange(offset, end)
            assembler.append(JSONObject()
                .put("transferId", "camera-1")
                .put("offset", offset)
                .put("data", Base64.getEncoder().encodeToString(chunk)))
            offset = end
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(source)
            .joinToString("") { "%02x".format(it) }
        val (mime, result) = assembler.finish(JSONObject()
            .put("transferId", "camera-1")
            .put("sha256", hash))
        assertEquals("image/jpeg", mime)
        assertArrayEquals(source, result)
    }

    @Test
    fun rejectsDuplicateOrOutOfOrderCameraChunk() {
        val assembler = SecureImageFrameAssembler()
        assembler.begin(JSONObject().put("transferId", "camera-2").put("totalBytes", 4).put("mimeType", "image/jpeg"))
        val chunk = Base64.getEncoder().encodeToString(byteArrayOf(1, 2))
        assembler.append(JSONObject().put("transferId", "camera-2").put("offset", 0).put("data", chunk))
        assertThrows(IllegalArgumentException::class.java) {
            assembler.append(JSONObject().put("transferId", "camera-2").put("offset", 0).put("data", chunk))
        }
    }

    @Test
    fun rejectsOversizedCameraFrameAndWrongDigest() {
        val assembler = SecureImageFrameAssembler()
        assertThrows(IllegalArgumentException::class.java) {
            assembler.begin(JSONObject()
                .put("transferId", "camera-3")
                .put("totalBytes", SecureImageFrameAssembler.MAX_IMAGE_BYTES + 1)
                .put("mimeType", "image/jpeg"))
        }
        assembler.begin(JSONObject().put("transferId", "camera-4").put("totalBytes", 1).put("mimeType", "image/jpeg"))
        assembler.append(JSONObject()
            .put("transferId", "camera-4")
            .put("offset", 0)
            .put("data", Base64.getEncoder().encodeToString(byteArrayOf(7))))
        assertThrows(IllegalArgumentException::class.java) {
            assembler.finish(JSONObject().put("transferId", "camera-4").put("sha256", "00".repeat(32)))
        }
    }
}
