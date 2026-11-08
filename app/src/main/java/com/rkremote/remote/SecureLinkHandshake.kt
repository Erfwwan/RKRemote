package com.rkremote.remote

import androidx.annotation.RequiresApi
import com.stonygrey.torann.HandshakeState
import com.stonygrey.torann.NoiseProtocol
import com.stonygrey.torann.Transport
import com.stonygrey.torann.KeyPair as NoiseKeyPair
import java.security.MessageDigest

/**
 * Three-message Noise XX handshake with signed P-256 identity binding.
 *
 * No application data can be read or written until the owner confirms the
 * short authentication string and the optional expected peer ID matches.
 * Any malformed/authentication-failed handshake is terminal; create a new session.
 */
@RequiresApi(33)
class SecureLinkHandshake(
    private val initiator: Boolean,
    private val localBinding: SecureLinkIdentityBinding,
    localStaticKey: NoiseKeyPair,
    private val expectedPeerId: String? = null
) : AutoCloseable {
    data class StepResult(
        val outboundFrame: ByteArray? = null,
        val peerBinding: SecureLinkIdentityBinding? = null,
        val confirmationCode: String? = null,
        val handshakeComplete: Boolean = false
    )

    private enum class State { NEW, WAITING_FOR_SECOND, WAITING_FOR_THIRD, COMPLETE, FAILED, CLOSED }

    private val protocol = NoiseProtocol.parse(SecureLinkStaticKeyStore.PROTOCOL_NAME)
    private val handshake: HandshakeState
    private var transport: Transport? = null
    private var remoteBinding: SecureLinkIdentityBinding? = null
    private var handshakeHash: ByteArray? = null
    private var state = State.NEW
    private var approved = false

    init {
        require(localBinding.verify()) { "Local identity binding is invalid" }
        require(localBinding.noiseStaticPublicKeyBase64 ==
            java.util.Base64.getEncoder().withoutPadding().encodeToString(localStaticKey.publicKey)) {
            "Noise static key does not match the signed local binding"
        }
        handshake = try {
            protocol.createHandshake(initiator = initiator, localStatic = localStaticKey)
        } finally {
            localStaticKey.close()
        }
        state = if (initiator) State.NEW else State.WAITING_FOR_SECOND
    }

    @Synchronized
    fun start(): ByteArray {
        checkState(State.NEW)
        return try {
            val first = handshake.writeMessage().message
            state = State.WAITING_FOR_SECOND
            first
        } catch (error: Throwable) {
            fail()
            throw error
        }
    }

    @Synchronized
    fun receive(frame: ByteArray): StepResult {
        require(frame.isNotEmpty() && frame.size <= MAX_FRAME_BYTES) { "Invalid Noise frame size" }
        check(state == State.WAITING_FOR_SECOND || state == State.WAITING_FOR_THIRD) {
            "Unexpected Noise handshake frame"
        }
        return try {
            if (initiator) receiveAsInitiator(frame) else receiveAsResponder(frame)
        } catch (error: Throwable) {
            fail()
            throw error
        }
    }

    private fun receiveAsInitiator(frame: ByteArray): StepResult {
        check(state == State.WAITING_FOR_SECOND)
        val received = handshake.readMessage(frame)
        val binding = SecureLinkIdentityBinding.parse(received.payload)
        validateRemoteBinding(binding)
        remoteBinding = binding

        val finalMessage = handshake.writeMessage(localBinding.toJson().toByteArray(Charsets.UTF_8))
        val completedTransport = finalMessage.transport
            ?: error("Noise XX initiator did not complete on message three")
        transport = completedTransport
        handshakeHash = finalMessage.handshakeHash
            ?: error("Noise XX handshake hash is missing")
        state = State.COMPLETE
        return StepResult(
            outboundFrame = finalMessage.message,
            peerBinding = binding,
            confirmationCode = confirmationCode(),
            handshakeComplete = true
        )
    }

    private fun receiveAsResponder(frame: ByteArray): StepResult {
        if (state == State.WAITING_FOR_SECOND) {
            val received = handshake.readMessage(frame)
            require(received.payload.isEmpty()) { "Unexpected payload in first Noise message" }
            val reply = handshake.writeMessage(localBinding.toJson().toByteArray(Charsets.UTF_8))
            state = State.WAITING_FOR_THIRD
            return StepResult(outboundFrame = reply.message)
        }

        check(state == State.WAITING_FOR_THIRD)
        val received = handshake.readMessage(frame)
        val binding = SecureLinkIdentityBinding.parse(received.payload)
        validateRemoteBinding(binding)
        remoteBinding = binding
        transport = received.transport ?: error("Noise XX responder did not complete on message three")
        handshakeHash = received.handshakeHash ?: error("Noise XX handshake hash is missing")
        state = State.COMPLETE
        return StepResult(
            peerBinding = binding,
            confirmationCode = confirmationCode(),
            handshakeComplete = true
        )
    }

    private fun validateRemoteBinding(binding: SecureLinkIdentityBinding) {
        require(binding.verify()) { "Remote peer identity signature is invalid" }
        if (expectedPeerId != null) {
            require(binding.peerId == expectedPeerId) { "Remote peer does not match the paired identity" }
        }
    }

    @Synchronized
    fun confirmPeer(displayedCode: String, expectedPeerId: String? = this.expectedPeerId) {
        checkState(State.COMPLETE)
        val peer = remoteBinding ?: error("Remote identity binding is unavailable")
        if (expectedPeerId != null) require(peer.peerId == expectedPeerId) {
            "Remote peer identity does not match the approved peer"
        }
        val expectedCode = confirmationCode()
        require(MessageDigest.isEqual(
            expectedCode.toByteArray(Charsets.US_ASCII),
            displayedCode.toByteArray(Charsets.US_ASCII)
        )) { "Short authentication string does not match" }
        approved = true
    }

    /** Noise transport is gated until the local owner confirms the peer identity and SAS. */
    @Synchronized
    fun encrypt(payload: ByteArray): ByteArray {
        check(approved && state == State.COMPLETE) { "Peer has not been locally approved" }
        require(payload.size <= MAX_FRAME_BYTES - Transport.TAG_SIZE) { "SecureLink payload is too large" }
        return transport?.writeMessage(payload) ?: error("Noise transport is unavailable")
    }

    @Synchronized
    fun decrypt(ciphertext: ByteArray): ByteArray {
        check(approved && state == State.COMPLETE) { "Peer has not been locally approved" }
        require(ciphertext.isNotEmpty() && ciphertext.size <= MAX_FRAME_BYTES) {
            "Invalid SecureLink ciphertext size"
        }
        return try {
            transport?.readMessage(ciphertext) ?: error("Noise transport is unavailable")
        } catch (error: Throwable) {
            fail()
            throw error
        }
    }

    @Synchronized
    fun remotePeerId(): String? = remoteBinding?.peerId

    @Synchronized
    fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        approved = false
        handshake.close()
        transport?.close()
        transport = null
        handshakeHash?.fill(0)
        handshakeHash = null
        remoteBinding = null
    }

    private fun confirmationCode(): String {
        val hash = handshakeHash ?: error("Handshake is not complete")
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "RKRemote-SecureLink-SAS-v1".toByteArray(Charsets.UTF_8) + hash
        )
        val value = ((digest[0].toLong() and 0xff) shl 24) or
            ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or
            (digest[3].toLong() and 0xff)
        return "%06d".format(java.util.Locale.ROOT, value % 1_000_000L)
    }

    private fun checkState(expected: State) {
        check(state == expected) { "SecureLink state is $state, expected $expected" }
    }

    private fun fail() {
        state = State.FAILED
        approved = false
        handshake.close()
        transport?.close()
        transport = null
        handshakeHash?.fill(0)
        handshakeHash = null
        remoteBinding = null
    }

    companion object {
        const val MAX_FRAME_BYTES = 12 * 1024

        /** Creates a session using the Android-Keystore-backed signing identity and encrypted X25519 key store. */
        @JvmStatic
        fun create(
            initiator: Boolean,
            identityStore: P2pIdentityStore,
            staticKeyStore: SecureLinkStaticKeyStore,
            expectedPeerId: String? = null
        ): SecureLinkHandshake {
            val stored = staticKeyStore.getOrCreate()
            return try {
                val binding = SecureLinkIdentityBinding.create(
                    identityStore,
                    stored.keyPair.publicKey,
                    stored.generation
                )
                SecureLinkHandshake(initiator, binding, stored.keyPair, expectedPeerId)
            } catch (error: Throwable) {
                stored.keyPair.close()
                throw error
            }
        }
    }
}
