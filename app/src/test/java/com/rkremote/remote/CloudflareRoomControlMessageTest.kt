package com.rkremote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudflareRoomControlMessageTest {
    @Test
    fun parsesKnownVersionedRoomEvent() {
        val event = CloudflareRoomControlMessage.parse(
            """{"v":1,"type":"peer-joined","payload":{"role":"target"}}"""
        )
        assertEquals("peer-joined", event.type)
        assertEquals("target", event.payload.getString("role"))
    }

    @Test
    fun acceptsKnownEventWithoutPayload() {
        val event = CloudflareRoomControlMessage.parse(
            """{"v":1,"type":"peer-left"}"""
        )
        assertEquals("peer-left", event.type)
        assertEquals(0, event.payload.length())
    }

    @Test
    fun rejectsMalformedOversizedUnknownAndWrongVersionMessages() {
        val invalid = listOf(
            "{",
            """{"v":2,"type":"peer-left","payload":{}}""",
            """{"v":1,"type":"unknown-event","payload":{}}""",
            """{"v":1,"type":"peer-left","payload":[]}"""
        )
        invalid.forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                CloudflareRoomControlMessage.parse(raw)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudflareRoomControlMessage.parse("x".repeat(CloudflareRoomControlMessage.MAX_BYTES + 1))
        }
    }
}
