package com.rkremote.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureRemoteCommandRouterTest {
    @Test
    fun dispatchRequiresOwnerGrantAndRegisteredHandler() {
        val router = SecureRemoteCommandRouter()
        var invoked = false
        router.register("screen.snapshot.request", RemoteCapability.SCREEN_VIEW) {
            invoked = true
            "screen.snapshot.result"
        }
        router.beginOwnerApprovedSession("paired-peer", setOf(RemoteCapability.FILES))

        val denied = router.dispatch("paired-peer", message("screen.snapshot.request", 0, "r1"))
        assertFalse(denied.accepted)
        assertFalse(invoked)

        val unregistered = router.dispatch("paired-peer", message("screen.input", 1, "r2"))
        assertFalse(unregistered.accepted)
        assertFalse(invoked)
    }

    @Test
    fun firstCapabilityGrantCanInitializeOwnerApprovedSession() {
        val router = SecureRemoteCommandRouter()
        assertFalse(router.hasActiveSession())
        router.register("file.list.request", RemoteCapability.FILES) { "file.list.result" }
        router.beginOwnerApprovedSession("paired-peer", setOf(RemoteCapability.SCREEN_VIEW))
        assertTrue(router.hasActiveSession())
        router.grantCapabilities(setOf(RemoteCapability.FILES))
        val files = router.dispatch("paired-peer", message("file.list.request", 0, "files"))
        assertTrue(files.accepted)
    }

    @Test
    fun dispatchesOnlyToOwnerApprovedPeerAndReturnsHandlerResponseType() {
        val router = SecureRemoteCommandRouter()
        var invoked = false
        router.register("file.list.request", RemoteCapability.FILES) {
            invoked = true
            "file.list.result"
        }
        router.beginOwnerApprovedSession("paired-peer", setOf(RemoteCapability.FILES))

        val wrongPeer = router.dispatch("different-peer", message("file.list.request", 0, "r1"))
        assertFalse(wrongPeer.accepted)
        assertFalse(invoked)

        val accepted = router.dispatch("paired-peer", message("file.list.request", 1, "r2"))
        assertTrue(accepted.accepted)
        assertTrue(invoked)
        assertEquals("file.list.result", accepted.responseType)
    }

    @Test
    fun rejectsDuplicateRequestIdsAndStopsDispatchAfterRevocation() {
        val router = SecureRemoteCommandRouter()
        var calls = 0
        router.register("screen.stream.start", RemoteCapability.SCREEN_VIEW) {
            calls++
            null
        }
        router.beginOwnerApprovedSession("paired-peer", setOf(RemoteCapability.SCREEN_VIEW))
        assertTrue(router.dispatch("paired-peer", message("screen.stream.start", 0, "same")).accepted)
        assertFalse(router.dispatch("paired-peer", message("screen.stream.start", 1, "same")).accepted)
        assertEquals(1, calls)

        router.endSession()
        assertFalse(router.dispatch("paired-peer", message("screen.stream.start", 2, "after-stop")).accepted)
        assertEquals(1, calls)
    }

    @Test
    fun requiresRequestIdAndConsumesSequenceWhenHandlerFails() {
        val router = SecureRemoteCommandRouter()
        router.register("file.download.request", RemoteCapability.FILES) {
            error("not available")
        }
        router.beginOwnerApprovedSession("paired-peer", setOf(RemoteCapability.FILES))
        assertFalse(router.dispatch("paired-peer", message("file.download.request", 0, "")).accepted)
        assertFalse(router.dispatch("paired-peer", message("file.download.request", 1, "failed")).accepted)
        assertFalse(router.dispatch("paired-peer", message("file.download.request", 1, "retry")).accepted)
    }

    private fun message(type: String, sequence: Long, requestId: String) = RemoteMessage(
        type = type,
        sessionId = "session",
        messageId = "message-$sequence",
        sequence = sequence,
        sentAt = 1_700_000_000_000L,
        payload = JSONObject().put("requestId", requestId)
    )
}
