package com.rkremote.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteProtocolTest {
    @Test
    fun roundTripsVersionedMessage() {
        val message = RemoteMessage(
            type = "heartbeat",
            sessionId = "session-1",
            messageId = "message-1",
            sequence = 4,
            sentAt = 1_700_000_000_000,
            payload = JSONObject().put("battery", 87)
        )
        val decoded = RemoteMessage.parse(message.toJson().toString())
        assertEquals(message.type, decoded.type)
        assertEquals(message.sessionId, decoded.sessionId)
        assertEquals(message.sequence, decoded.sequence)
        assertEquals(87, decoded.payload.getInt("battery"))
    }

    @Test
    fun rejectsUnknownMessageType() {
        val raw = JSONObject()
            .put("protocolVersion", 1)
            .put("type", "shell.exec")
            .put("sessionId", "s")
            .put("messageId", "m")
            .put("sequence", 0)
            .put("sentAt", 1)
            .put("payload", JSONObject())
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
            RemoteMessage.parse(raw)
        }
    }

    @Test
    fun rejectsOversizedMessage() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteMessage.parse(" ".repeat(RemoteMessage.MAX_MESSAGE_BYTES + 1))
        }
    }
}
