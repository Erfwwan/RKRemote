package com.rkremote.remote

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudflareSignalingClientTest {
    private val listener = object : CloudflareSignalingClient.Listener {
        override fun onConnected() = Unit
        override fun onEncryptedFrame(frame: ByteArray) = Unit
        override fun onRoomEvent(type: String, payload: JSONObject) = Unit
        override fun onDisconnected(reason: String) = Unit
        override fun onFailure(error: Throwable) = Unit
    }

    @Test
    fun acceptsOnlySecureWorkerOriginAndRoomId() {
        CloudflareSignalingClient(
            "wss://rkremote.example.workers.dev",
            "a".repeat(64),
            CloudflareSignalingClient.Role.TARGET,
            listener
        )
        CloudflareSignalingClient(
            "wss://rkremote.example.workers.dev/",
            "b".repeat(64),
            CloudflareSignalingClient.Role.CONTROLLER,
            listener
        )
    }

    @Test
    fun rejectsPlaintextAndCredentialedSignalingUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudflareSignalingClient(
                "ws://rkremote.example.workers.dev",
                "a".repeat(64),
                CloudflareSignalingClient.Role.TARGET,
                listener
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudflareSignalingClient(
                "not-a-valid-origin",
                "a".repeat(64),
                CloudflareSignalingClient.Role.TARGET,
                listener
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudflareSignalingClient(
                "wss://user:secret@rkremote.example.workers.dev",
                "a".repeat(64),
                CloudflareSignalingClient.Role.TARGET,
                listener
            )
        }
    }

    @Test
    fun rejectsCustomPathsQueriesFragmentsAndMalformedRoomIds() {
        val invalidUrls = listOf(
            "wss://rkremote.example.workers.dev/custom",
            "wss://rkremote.example.workers.dev?token=secret",
            "wss://rkremote.example.workers.dev#fragment"
        )
        invalidUrls.forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                CloudflareSignalingClient(
                    url,
                    "a".repeat(64),
                    CloudflareSignalingClient.Role.TARGET,
                    listener
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudflareSignalingClient(
                "wss://rkremote.example.workers.dev",
                "not-a-256-bit-room",
                CloudflareSignalingClient.Role.TARGET,
                listener
            )
        }
    }

    @Test
    fun closeIsTerminalAndIdempotent() {
        val client = CloudflareSignalingClient(
            "wss://rkremote.example.workers.dev",
            "c".repeat(64),
            CloudflareSignalingClient.Role.TARGET,
            listener
        )
        client.close()
        client.close()

        assertThrows(IllegalStateException::class.java) {
            client.connect()
        }
        org.junit.Assert.assertFalse(client.sendEncryptedFrame(byteArrayOf(1)))
    }

}
