package com.rkremote.remote

import org.json.JSONObject

/**
 * Versioned, bounded message envelope shared by the agent and controller.
 * Unknown message types are rejected; this class never executes commands.
 */
data class RemoteMessage(
    val type: String,
    val sessionId: String,
    val messageId: String,
    val sequence: Long,
    val sentAt: Long,
    val payload: JSONObject,
    val protocolVersion: Int = CURRENT_VERSION
) {
    fun toJson(): JSONObject = JSONObject()
        .put("protocolVersion", protocolVersion)
        .put("type", type)
        .put("sessionId", sessionId)
        .put("messageId", messageId)
        .put("sequence", sequence)
        .put("sentAt", sentAt)
        .put("payload", payload)

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_MESSAGE_BYTES = 64 * 1024

        private val allowedTypes = setOf(
            "hello", "pair.request", "pair.approve", "pair.reject",
            "session.start", "session.stop", "heartbeat", "ack",
            "capability.request", "capability.result", "notification.event",
            "file.list.request", "file.list.result", "file.transfer.request",
            "screen.start.request", "screen.stop", "camera.start.request",
            "camera.stop", "audio.start.request", "audio.stop",
            "ptt.start", "ptt.stop", "error"
        )

        fun parse(raw: String): RemoteMessage {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
                "Message exceeds the maximum size"
            }
            val json = JSONObject(raw)
            val version = json.getInt("protocolVersion")
            require(version == CURRENT_VERSION) { "Unsupported protocol version" }
            val type = json.getString("type")
            require(type in allowedTypes) { "Unknown message type" }

            val sessionId = json.getString("sessionId")
            val messageId = json.getString("messageId")
            require(sessionId.length in 1..128)
            require(messageId.length in 1..128)

            val sequence = json.getLong("sequence")
            require(sequence >= 0)
            val sentAt = json.getLong("sentAt")
            val payload = json.optJSONObject("payload") ?: JSONObject()
            return RemoteMessage(type, sessionId, messageId, sequence, sentAt, payload, version)
        }
    }
}
