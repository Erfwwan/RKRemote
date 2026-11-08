package com.rkremote.remote

import com.stonygrey.torann.NoiseProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SecureLinkHandshakeTest {
    private data class TestIdentity(
        val peerId: String,
        val publicKeyBase64: String,
        val privateKey: java.security.PrivateKey
    )

    private fun identity(): TestIdentity {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(pair.public.encoded)
        return TestIdentity(P2pPeerRecord.peerIdFor(pair.public.encoded), encoded, pair.private)
    }

    private fun binding(identity: TestIdentity, noisePublicKey: ByteArray): SecureLinkIdentityBinding {
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

    @Test
    fun xxHandshakeAuthenticatesBothIdentitiesAndExchangesCiphertext() {
        val protocol = NoiseProtocol.parse(SecureLinkStaticKeyStore.PROTOCOL_NAME)
        val initiatorKey = protocol.generateKeyPair()
        val responderKey = protocol.generateKeyPair()
        val initiatorIdentity = identity()
        val responderIdentity = identity()
        val initiator = SecureLinkHandshake(
            true, binding(initiatorIdentity, initiatorKey.publicKey), initiatorKey,
            responderIdentity.peerId
        )
        val responder = SecureLinkHandshake(
            false, binding(responderIdentity, responderKey.publicKey), responderKey,
            initiatorIdentity.peerId
        )

        val first = initiator.start()
        val second = responder.receive(first).outboundFrame!!
        val third = initiator.receive(second)
        assertTrue(third.handshakeComplete)
        assertEquals(responderIdentity.peerId, third.peerBinding!!.peerId)
        val responderDone = responder.receive(third.outboundFrame!!)
        assertTrue(responderDone.handshakeComplete)
        assertEquals(initiatorIdentity.peerId, responderDone.peerBinding!!.peerId)
        assertEquals(third.confirmationCode, responderDone.confirmationCode)

        val code = third.confirmationCode!!
        assertThrows(IllegalStateException::class.java) {
            initiator.encrypt("must wait for owner approval".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            initiator.confirmPeer("not-the-code", responderIdentity.peerId)
        }
        initiator.confirmPeer(code, responderIdentity.peerId)
        responder.confirmPeer(code, initiatorIdentity.peerId)

        val clear = "RKRemote secure channel".toByteArray()
        val encrypted = initiator.encrypt(clear)
        assertTrue(!encrypted.contentEquals(clear))
        assertArrayEquals(clear, responder.decrypt(encrypted))

        // A duplicate/out-of-order handshake frame after establishment must
        // invalidate the transport, not merely throw while leaving it usable.
        assertThrows(IllegalStateException::class.java) {
            initiator.receive(byteArrayOf(0x01))
        }
        assertThrows(IllegalStateException::class.java) {
            initiator.encrypt("must fail closed".toByteArray())
        }
        initiator.close()
        responder.close()
    }

    @Test
    fun rejectsWrongPinnedPeerIdentity() {
        val protocol = NoiseProtocol.parse(SecureLinkStaticKeyStore.PROTOCOL_NAME)
        val initiatorKey = protocol.generateKeyPair()
        val responderKey = protocol.generateKeyPair()
        val initiatorIdentity = identity()
        val responderIdentity = identity()
        val initiator = SecureLinkHandshake(
            true, binding(initiatorIdentity, initiatorKey.publicKey), initiatorKey,
            "0".repeat(64)
        )
        val responder = SecureLinkHandshake(
            false, binding(responderIdentity, responderKey.publicKey), responderKey
        )
        val second = responder.receive(initiator.start()).outboundFrame!!
        assertThrows(IllegalArgumentException::class.java) { initiator.receive(second) }
        initiator.close()
        responder.close()
    }

    @Test
    fun rejectsForgedBindingSignature() {
        val identity = identity()
        val protocol = NoiseProtocol.parse(SecureLinkStaticKeyStore.PROTOCOL_NAME)
        val key = protocol.generateKeyPair()
        val valid = binding(identity, key.publicKey)
        val forged = valid.copy(generation = 2L)
        assertTrue(valid.verify())
        assertTrue(!forged.verify())
        key.close()
    }
}
