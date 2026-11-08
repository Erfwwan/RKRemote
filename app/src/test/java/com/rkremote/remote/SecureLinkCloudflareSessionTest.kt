package com.rkremote.remote

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class SecureLinkCloudflareSessionTest {
    private data class Identity(
        val peerId: String,
        val publicKeyBase64: String,
        val privateKey: java.security.PrivateKey
    )

    private fun identity(): Identity {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        return Identity(
            P2pPeerRecord.peerIdFor(pair.public.encoded),
            Base64.getEncoder().withoutPadding().encodeToString(pair.public.encoded),
            pair.private
        )
    }

    private fun binding(identity: Identity, noisePublicKey: ByteArray): SecureLinkIdentityBinding {
        val noiseBase64 = Base64.getEncoder().withoutPadding().encodeToString(noisePublicKey)
        val canonical = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF("RK-SecureLink-KeyBinding-v1")
                out.writeInt(1)
                out.writeLong(1L)
                out.writeUTF(identity.peerId)
                out.writeUTF(identity.publicKeyBase64)
                out.writeUTF(noiseBase64)
            }
        }.toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.privateKey)
            update(canonical)
            sign()
        }
        return SecureLinkIdentityBinding(
            1, 1L, identity.peerId, identity.publicKeyBase64, noiseBase64,
            Base64.getEncoder().withoutPadding().encodeToString(signature)
        )
    }

    private class FakeChannel : SecureLinkCloudflareSession.FrameChannel {
        private var listener: SecureLinkCloudflareSession.FrameChannel.Listener? = null
        lateinit var peer: FakeChannel
        private var connected = false

        override fun setListener(listener: SecureLinkCloudflareSession.FrameChannel.Listener) {
            this.listener = listener
        }

        override fun connect() {
            connected = true
            listener?.onConnected()
        }

        override fun send(frame: ByteArray): Boolean {
            if (!connected || !peer.connected) return false
            peer.listener?.onFrame(frame.copyOf())
            return true
        }

        override fun close() {
            connected = false
        }
    }

    private class RecordingListener : SecureLinkCloudflareSession.Listener {
        var peerId: String? = null
        var sas: String? = null
        var readyCount = 0
        var lastApplicationFrame: ByteArray? = null
        var failure: Throwable? = null

        override fun onPeerVerificationRequired(peerId: String, shortAuthenticationString: String) {
            this.peerId = peerId
            this.sas = shortAuthenticationString
        }
        override fun onSecureReady(peerId: String) { readyCount++ }
        override fun onApplicationFrame(payload: ByteArray) { lastApplicationFrame = payload }
        override fun onRoomEvent(type: String, payload: JSONObject) = Unit
        override fun onDisconnected(reason: String) = Unit
        override fun onFailure(error: Throwable) { failure = error }
    }

    @Test
    fun cloudflareBridgeRequiresBothOwnersAndThenCarriesEncryptedApplicationFrames() {
        val protocol = com.stonygrey.torann.NoiseProtocol.parse(SecureLinkStaticKeyStore.PROTOCOL_NAME)
        val controllerNoise = protocol.generateKeyPair()
        val targetNoise = protocol.generateKeyPair()
        val controllerIdentity = identity()
        val targetIdentity = identity()

        val controllerHandshake = SecureLinkHandshake(
            true, binding(controllerIdentity, controllerNoise.publicKey), controllerNoise,
            targetIdentity.peerId
        )
        val targetHandshake = SecureLinkHandshake(
            false, binding(targetIdentity, targetNoise.publicKey), targetNoise,
            controllerIdentity.peerId
        )

        val controllerChannel = FakeChannel()
        val targetChannel = FakeChannel()
        controllerChannel.peer = targetChannel
        targetChannel.peer = controllerChannel

        val controllerEvents = RecordingListener()
        val targetEvents = RecordingListener()
        val controller = SecureLinkCloudflareSession(
            controllerHandshake, true, controllerChannel, controllerEvents
        )
        val target = SecureLinkCloudflareSession(
            targetHandshake, false, targetChannel, targetEvents
        )

        target.connect()
        controller.connect()

        assertEquals(targetIdentity.peerId, controllerEvents.peerId)
        assertEquals(controllerIdentity.peerId, targetEvents.peerId)
        assertEquals(controllerEvents.sas, targetEvents.sas)
        assertFalse(controller.sendApplication("must wait".toByteArray()))

        controller.approvePeer(controllerEvents.sas!!, targetIdentity.peerId)
        assertFalse(controller.sendApplication("target has not approved yet".toByteArray()))
        target.approvePeer(targetEvents.sas!!, controllerIdentity.peerId)

        assertEquals(1, controllerEvents.readyCount)
        assertEquals(1, targetEvents.readyCount)
        val message = "encrypted bridge payload".toByteArray()
        assertTrue(controller.sendApplication(message))
        assertArrayEquals(message, targetEvents.lastApplicationFrame)

        controller.close()
        target.close()
        assertTrue(controllerEvents.failure == null)
        assertTrue(targetEvents.failure == null)
    }
}
