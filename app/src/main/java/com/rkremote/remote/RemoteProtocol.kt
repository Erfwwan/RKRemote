package com.rkremote.remote

import org.json.JSONException
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
            "hello", "pair.request", "pair.approve", "pair.reject", "secure.ping", "secure.pong", "command.result", "diagnostics.request", "diagnostics.result",
            "session.start", "session.stop", "heartbeat", "ack",
            "capability.request", "capability.result", "notification.event",
            "file.list.request", "file.list.result", "file.transfer.request", "file.download.request", "file.download.begin", "file.download.chunk", "file.download.complete", "file.download.error", "file.upload.begin", "file.transfer.chunk", "file.transfer.complete", "file.transfer.cancel", "notifications.sync.request", "notifications.sync.result", "calllog.sync.request", "calllog.sync.result", "sms.sync.request", "sms.sync.result", "contacts.sync.request", "contacts.sync.result", "location.sync.request", "location.sync.result",
            "screen.input", "screen.stream.start", "screen.stream.stop", "screen.video.config", "screen.video.chunk", "screen.video.end", "camera.snapshot.request", "camera.frame.begin", "camera.frame.chunk", "camera.frame.complete",
            "ptt.start", "ptt.stop", "audio.chunk", "ptt.audio.chunk", "error"
        )

        fun parse(raw: String): RemoteMessage {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
                "Message exceeds the maximum size"
            }
            try {
                val json = JSONObject(raw)
                val versionValue = json.opt("protocolVersion") as? Number
                    ?: throw IllegalArgumentException("Protocol version must be numeric")
                require(versionValue.toDouble() == CURRENT_VERSION.toDouble()) {
                    "Unsupported protocol version"
                }
                val version = versionValue.toInt()
                val type = json.opt("type") as? String
                    ?: throw IllegalArgumentException("Message type must be a string")
                require(type in allowedTypes) { "Unknown message type" }

                val sessionId = json.opt("sessionId") as? String
                    ?: throw IllegalArgumentException("Session ID must be a string")
                val messageId = json.opt("messageId") as? String
                    ?: throw IllegalArgumentException("Message ID must be a string")
                require(sessionId.isNotBlank() && sessionId.length <= 128)
                require(messageId.isNotBlank() && messageId.length <= 128)

                val sequenceValue = json.opt("sequence") as? Number
                    ?: throw IllegalArgumentException("Sequence must be numeric")
                val sequence = sequenceValue.toLong()
                require(sequence >= 0 && sequenceValue.toDouble() == sequence.toDouble()) {
                    "Invalid message sequence"
                }
                val sentAtValue = json.opt("sentAt") as? Number
                    ?: throw IllegalArgumentException("Message timestamp must be numeric")
                val sentAt = sentAtValue.toLong()
                require(sentAt >= 0 && sentAtValue.toDouble() == sentAt.toDouble()) {
                    "Invalid message timestamp"
                }
                val payload = json.opt("payload") as? JSONObject
                    ?: throw IllegalArgumentException("Message payload must be an object")
                require(payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
                    "Payload exceeds protocol limit"
                }
                return RemoteMessage(type, sessionId, messageId, sequence, sentAt, payload, version)
            } catch (failure: JSONException) {
                throw IllegalArgumentException("Malformed remote message", failure)
            }
        }
    }
}
