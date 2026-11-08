package com.rkremote.remote

import java.util.LinkedHashMap

/**
 * Per-connection inbound replay/session guard for authenticated DataChannel messages.
 * The caller must already have authenticated the remote peer at the transport layer.
 */
class SecureMessageGuard(
    private val expectedSessionId: String,
    private val maxClockSkewMillis: Long = DEFAULT_CLOCK_SKEW_MS,
    private val maxRememberedMessageIds: Int = DEFAULT_REMEMBERED_IDS
) {
    private var lastSequence = -1L
    private val seenIds = object : LinkedHashMap<String, Boolean>(maxRememberedMessageIds, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > maxRememberedMessageIds
    }

    init {
        require(expectedSessionId.isNotBlank() && expectedSessionId.length <= 128)
        require(maxClockSkewMillis >= 0)
        require(maxRememberedMessageIds in 1..4096)
    }

    @Synchronized
    fun accept(message: RemoteMessage, nowMillis: Long = System.currentTimeMillis()) {
        require(message.sessionId == expectedSessionId) { "Message belongs to another session" }
        require(message.sentAt >= nowMillis - maxClockSkewMillis &&
            message.sentAt <= nowMillis + maxClockSkewMillis) {
            "Message timestamp is outside the accepted window"
        }
        require(message.sequence > lastSequence) { "Replayed or out-of-order message sequence" }
        require(!seenIds.containsKey(message.messageId)) { "Duplicate message ID" }
        seenIds[message.messageId] = true
        lastSequence = message.sequence
    }

    companion object {
        const val DEFAULT_CLOCK_SKEW_MS = 10 * 60 * 1000L
        const val DEFAULT_REMEMBERED_IDS = 512
    }
}
