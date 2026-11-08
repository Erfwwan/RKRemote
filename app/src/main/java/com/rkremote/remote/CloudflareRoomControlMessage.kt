package com.rkremote.remote

import org.json.JSONException
import org.json.JSONObject

/** Strict parser for the small, non-sensitive lifecycle events emitted by the Worker. */
data class CloudflareRoomControlMessage(
    val type: String,
    val payload: JSONObject
) {
    companion object {
        const val MAX_BYTES = 1024
        private val ALLOWED_TYPES = setOf("peer-joined", "peer-left", "peer-not-ready")

        fun parse(raw: String): CloudflareRoomControlMessage {
            require(raw.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES) {
                "Room control message size is invalid"
            }
            try {
                val json = JSONObject(raw)
                require(json.optInt("v", -1) == 1) { "Unsupported room-control version" }
                val type = json.optString("type")
                require(type in ALLOWED_TYPES) { "Unexpected text message from signaling server" }
                val payload = if (json.has("payload")) {
                    json.getJSONObject("payload")
                } else {
                    JSONObject()
                }
                return CloudflareRoomControlMessage(type, payload)
            } catch (failure: JSONException) {
                throw IllegalArgumentException("Malformed room-control message", failure)
            }
        }
    }
}
