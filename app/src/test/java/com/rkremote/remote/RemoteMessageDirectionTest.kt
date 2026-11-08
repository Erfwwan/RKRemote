package com.rkremote.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMessageDirectionTest {
    @Test
    fun targetOnlyConsumesControllerToTargetPttAudio() {
        assertTrue(
            RemoteMessageDirection.isAllowedIncoming(
                CloudflareSignalingClient.Role.TARGET,
                "ptt.audio.chunk"
            )
        )
        assertFalse(
            RemoteMessageDirection.isAllowedIncoming(
                CloudflareSignalingClient.Role.CONTROLLER,
                "ptt.audio.chunk"
            )
        )
    }

    @Test
    fun controllerOnlyConsumesTargetResponsesAndMedia() {
        val targetResponses = listOf(
            "camera.frame.begin",
            "camera.frame.chunk",
            "camera.frame.complete",
            "file.list.result",
            "command.result",
            "file.download.begin",
            "file.download.chunk",
            "file.download.complete",
            "file.download.error",
            "sms.sync.result",
            "location.sync.result",
            "contacts.sync.result",
            "calllog.sync.result",
            "notifications.sync.result",
            "screen.video.config",
            "screen.video.chunk",
            "screen.video.end",
            "audio.chunk"
        )
        targetResponses.forEach { type ->
            assertTrue(RemoteMessageDirection.isAllowedIncoming(CloudflareSignalingClient.Role.CONTROLLER, type))
            assertFalse(RemoteMessageDirection.isAllowedIncoming(CloudflareSignalingClient.Role.TARGET, type))
        }
    }

    @Test
    fun sharedControlMessagesRemainAvailableToBothRoles() {
        assertTrue(RemoteMessageDirection.isAllowedIncoming(CloudflareSignalingClient.Role.TARGET, "secure.ping"))
        assertTrue(RemoteMessageDirection.isAllowedIncoming(CloudflareSignalingClient.Role.CONTROLLER, "secure.ping"))
    }
}
