package com.rkremote.remote

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Bounded, ordered JPEG/PNG frame assembly for secure camera snapshots. */
class SecureImageFrameAssembler {
    private data class Transfer(
        val id: String,
        val expectedBytes: Int,
        val mimeType: String,
        val output: ByteArrayOutputStream,
        var nextOffset: Int = 0
    )

    private var active: Transfer? = null

    @Synchronized
    fun begin(payload: JSONObject) {
        abort()
        val id = payload.getString("transferId")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val total = payload.getInt("totalBytes")
        require(total in 1..MAX_IMAGE_BYTES)
        val mime = payload.getString("mimeType")
        require(mime == "image/jpeg" || mime == "image/png")
        active = Transfer(id, total, mime, ByteArrayOutputStream(total.coerceAtMost(256 * 1024)))
    }

    @Synchronized
    fun append(payload: JSONObject) {
        val transfer = requireActive(payload)
        val offset = payload.getInt("offset")
        require(offset == transfer.nextOffset) { "Camera frame chunk is missing, duplicated or out of order" }
        val encoded = payload.getString("data")
        require(encoded.length <= MAX_CHUNK_BYTES * 2) { "Camera frame chunk is too large" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.isNotEmpty() && bytes.size <= MAX_CHUNK_BYTES)
        require(transfer.nextOffset + bytes.size <= transfer.expectedBytes) { "Camera frame exceeds announced size" }
        transfer.output.write(bytes)
        transfer.nextOffset += bytes.size
    }

    @Synchronized
    fun finish(payload: JSONObject): Pair<String, ByteArray> {
        val transfer = requireActive(payload)
        require(transfer.nextOffset == transfer.expectedBytes) { "Camera frame is incomplete" }
        val bytes = transfer.output.toByteArray()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual.equals(payload.getString("sha256"), ignoreCase = true)) {
            "Camera frame SHA-256 does not match"
        }
        active = null
        return transfer.mimeType to bytes
    }

    @Synchronized
    fun abort() {
        active?.output?.reset()
        active = null
    }

    private fun requireActive(payload: JSONObject): Transfer {
        val transfer = active ?: throw IllegalStateException("No camera frame transfer is active")
        require(transfer.id == payload.optString("transferId")) { "Unexpected camera frame transfer ID" }
        return transfer
    }

    companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_CHUNK_BYTES = 24 * 1024
    }
}
