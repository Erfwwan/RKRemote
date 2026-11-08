package com.rkremote.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureNotificationSyncTest {
    @Test
    fun limitsEventCountAndMarksTruncatedResponse() {
        val events = (0 until 30).map { JSONObject().put("title", "event-$it") }
        val response = SecureNotificationSync.buildBoundedResponse("request-1", 30, events)
        assertEquals(20, response.getInt("returned"))
        assertEquals(30, response.getInt("available"))
        assertTrue(response.getBoolean("truncated"))
        assertTrue(response.toString().toByteArray(Charsets.UTF_8).size <= SecureNotificationSync.MAX_RESPONSE_BYTES)
    }

    @Test
    fun boundsHeavilyEscapedNotificationText() {
        val huge = JSONObject().put("text", "\\"".repeat(20_000))
        val response = SecureNotificationSync.buildBoundedResponse("request-2", 1, listOf(huge))
        assertEquals(0, response.getInt("returned"))
        assertTrue(response.getBoolean("truncated"))
        assertTrue(response.toString().toByteArray(Charsets.UTF_8).size <= SecureNotificationSync.MAX_RESPONSE_BYTES)
    }
}
