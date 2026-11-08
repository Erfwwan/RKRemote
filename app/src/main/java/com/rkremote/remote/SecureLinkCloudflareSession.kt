package com.rkremote.remote

import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Joins the Noise XX engine to the opaque Cloudflare binary rendezvous.
 *
 * Application frames remain blocked until:
 *  - this device's owner confirms the SAS and peer identity; and
 *  - the remote device sends the encrypted READY acknowledgement after its own approval.
 *
 * All callbacks are invoked on the OkHttp callback thread; UI callers must dispatch to main.
 */
@RequiresApi(33)
class SecureLinkCloudflareSession internal constructor(
    private val handshake: SecureLinkHandshake,
    private val initiator: Boolean,
    private val channel: FrameChannel,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onPeerVerificationRequired(peerId: String, shortAuthenticationString: String)
        fun onSecureReady(peerId: String)
        fun onApplicationFrame(payload: ByteArray)
        fun onRoomEvent(type: String, payload: JSONObject)
        fun onDisconnected(reason: String)
        fun onFailure(error: Throwable)
    }

    internal interface FrameChannel : AutoCloseable {
        interface Listener {
            fun onConnected()
            fun onFrame(frame: ByteArray)
            fun onRoomEvent(type: String, payload: JSONObject)
            fun onDisconnected(reason: String)
            fun onFailure(error: Throwable)
        }
        fun setListener(listener: Listener)
        fun connect()
        fun send(frame: ByteArray): Boolean
    }

    private var handshakeComplete = false
    private var localApproved = false
    private var remoteApproved = false
    private var readyNotified = false
    private var closed = false
    private val pendingInbound = ArrayDeque<ByteArray>()

    init {
        channel.setListener(object : FrameChannel.Listener {
            override fun onConnected() {
                synchronized(this@SecureLinkCloudflareSession) {
                    if (closed) return
                    try {
                        if (initiator) sendRequired(handshake.start())
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }
            }

            override fun onFrame(frame: ByteArray) {
                synchronized(this@SecureLinkCloudflareSession) {
                    if (closed) return
                    try {
                        if (!handshakeComplete) {
                            val result = handshake.receive(frame)
                            result.outboundFrame?.let(::sendRequired)
                            if (result.handshakeComplete) {
                                handshakeComplete = true
                                val peerId = result.peerBinding?.peerId
                                    ?: error("Authenticated peer identity is missing")
                                val code = result.confirmationCode
                                    ?: error("SecureLink confirmation code is missing")
                                listener.onPeerVerificationRequired(peerId, code)
                            }
                        } else if (!localApproved) {
                            if (pendingInbound.size >= MAX_PENDING_FRAMES) {
                                error("Too many encrypted frames arrived before local approval")
                            }
                            pendingInbound.addLast(frame.copyOf())
                        } else {
                            processTransportFrame(frame)
                        }
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }
            }

            override fun onRoomEvent(type: String, payload: JSONObject) {
                synchronized(this@SecureLinkCloudflareSession) {
                    if (closed) return
                    if (type == "peer-left") {
                        listener.onDisconnected("The paired device left the signaling room")
                        close()
                    } else {
                        // Serialize room events with close() so a late WebSocket callback
                        // cannot update a disposed screen or a replacement session.
                        listener.onRoomEvent(type, payload)
                    }
                }
            }

            override fun onDisconnected(reason: String) {
                synchronized(this@SecureLinkCloudflareSession) {
                    if (!closed) {
                        listener.onDisconnected(reason)
                        close()
                    }
                }
            }

            override fun onFailure(error: Throwable) {
                synchronized(this@SecureLinkCloudflareSession) {
                    if (!closed) fail(error)
                }
            }
        })
    }

    /** Start the WSS rendezvous. Target should connect before controller. */
    @Synchronized
    fun connect() {
        check(!closed) { "SecureLink session is closed" }
        channel.connect()
    }

    /**
     * Must be called only after the owner has visually compared the SAS on both devices.
     * The supplied peer ID must match the identity shown to the owner.
     */
    @Synchronized
    fun approvePeer(shortAuthenticationString: String, expectedPeerId: String) {
        check(!closed && handshakeComplete) { "Peer handshake is not complete" }
        check(!localApproved) { "Peer has already been approved locally" }
        handshake.confirmPeer(shortAuthenticationString, expectedPeerId)
        localApproved = true
        sendRequired(handshake.encrypt(byteArrayOf(READY_MESSAGE)))
        while (pendingInbound.isNotEmpty()) {
            processTransportFrame(pendingInbound.removeFirst())
        }
        notifyReadyIfComplete()
    }

    /** Sends an application payload only after both device owners have approved the pairing. */
    @Synchronized
    fun sendApplication(payload: ByteArray): Boolean {
        if (closed || !isReady()) return false
        require(payload.size <= SecureLinkHandshake.MAX_FRAME_BYTES - 1) {
            "SecureLink application payload is too large"
        }
        return channel.send(handshake.encrypt(byteArrayOf(APP_MESSAGE) + payload))
    }

    private fun processTransportFrame(frame: ByteArray) {
        val clear = handshake.decrypt(frame)
        if (clear.isEmpty()) error("Empty SecureLink transport message")
        when (clear[0]) {
            READY_MESSAGE -> {
                require(clear.size == 1) { "Malformed SecureLink READY message" }
                require(!remoteApproved) { "Duplicate SecureLink READY message" }
                remoteApproved = true
                notifyReadyIfComplete()
            }
            APP_MESSAGE -> {
                require(isReady()) { "Application data arrived before mutual approval" }
                listener.onApplicationFrame(clear.copyOfRange(1, clear.size))
            }
            else -> error("Unknown SecureLink application frame type")
        }
    }

    private fun notifyReadyIfComplete() {
        if (!readyNotified && isReady()) {
            readyNotified = true
            listener.onSecureReady(handshake.remotePeerId() ?: error("Peer identity is missing"))
        }
    }

    private fun isReady(): Boolean = handshakeComplete && localApproved && remoteApproved

    private fun sendRequired(frame: ByteArray) {
        if (!channel.send(frame)) error("Cloudflare signaling socket rejected an outbound frame")
    }

    private fun fail(error: Throwable) {
        if (closed) return
        closed = true
        pendingInbound.clear()
        try {
            handshake.close()
        } finally {
            try {
                channel.close()
            } finally {
                listener.onFailure(error)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pendingInbound.clear()
        try {
            handshake.close()
        } finally {
            channel.close()
        }
    }

    companion object {
        private const val READY_MESSAGE: Byte = 0
        private const val APP_MESSAGE: Byte = 1
        private const val MAX_PENDING_FRAMES = 8

        /**
         * Creates a Cloudflare-backed session. Controller initiates Noise XX;
         * target waits for the first handshake frame.
         */
        @JvmStatic
        fun create(
            baseWssUrl: String,
            roomId: String,
            role: CloudflareSignalingClient.Role,
            handshake: SecureLinkHandshake,
            listener: Listener
        ): SecureLinkCloudflareSession {
            val adapter = CloudflareFrameChannel(baseWssUrl, roomId, role)
            return SecureLinkCloudflareSession(
                handshake,
                initiator = role == CloudflareSignalingClient.Role.CONTROLLER,
                channel = adapter,
                listener = listener
            )
        }
    }

    private class CloudflareFrameChannel(
        baseWssUrl: String,
        roomId: String,
        role: CloudflareSignalingClient.Role
    ) : FrameChannel {
        private var frameListener: FrameChannel.Listener? = null
        private val client = CloudflareSignalingClient(
            baseWssUrl, roomId, role,
            object : CloudflareSignalingClient.Listener {
                override fun onConnected() { frameListener?.onConnected() }
                override fun onEncryptedFrame(frame: ByteArray) { frameListener?.onFrame(frame) }
                override fun onRoomEvent(type: String, payload: JSONObject) {
                    frameListener?.onRoomEvent(type, payload)
                }
                override fun onDisconnected(reason: String) {
                    frameListener?.onDisconnected(reason)
                }
                override fun onFailure(error: Throwable) { frameListener?.onFailure(error) }
            }
        )

        override fun setListener(listener: FrameChannel.Listener) {
            frameListener = listener
        }
        override fun connect() = client.connect()
        override fun send(frame: ByteArray): Boolean = client.sendEncryptedFrame(frame)
        override fun close() {
            try {
                client.close()
            } finally {
                frameListener = null
            }
        }
    }
}
