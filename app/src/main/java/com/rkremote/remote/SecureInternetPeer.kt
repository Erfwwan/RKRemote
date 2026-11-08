package com.rkremote.remote

import android.content.Context
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import java.util.UUID
import java.util.LinkedHashMap

/**
 * End-to-end internet connection coordinator:
 * Cloudflare rendezvous -> Noise XX + mutual owner approval -> WebRTC ICE/DTLS DataChannel.
 *
 * Cloudflare carries only Noise ciphertext. SDP and ICE are encrypted Noise application
 * messages. Once WebRTC is connected, application bytes use the WebRTC DataChannel,
 * protected by DTLS. This coordinator does not silently fall back to plaintext/LAN.
 */
@RequiresApi(33)
class SecureInternetPeer(
    context: Context,
    baseWssUrl: String,
    roomId: String,
    private val role: CloudflareSignalingClient.Role,
    handshake: SecureLinkHandshake,
    identityStore: P2pIdentityStore,
    turnServers: List<SecureWebRtcPeer.TurnServer> = emptyList(),
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onPeerVerificationRequired(peerId: String, shortAuthenticationString: String)
        fun onSecureSignalingReady(peerId: String)
        fun onWebRtcConnecting()
        fun onWebRtcConnected()
        fun onData(payload: ByteArray)
        fun onRemoteMessage(message: RemoteMessage) {}
        fun onRoomEvent(type: String, payload: JSONObject)
        fun onDisconnected(reason: String)
        fun onFailure(error: Throwable)
    }

    private val appContext = context.applicationContext
    private val localPeerId = identityStore.getOrCreateIdentity().peerId
    private val turnConfig = turnServers.toList()
    private val closed = AtomicBoolean(false)
    private val outboundSequence = AtomicLong(0)
    private val inboundSequence = AtomicLong(-1)
    private val sessionId = MessageDigest.getInstance("SHA-256")
        .digest(roomId.toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }
    private val seenMessageIds = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 512
    }
    private val pendingSignals = ArrayDeque<ByteArray>()
    private val lock = Any()
    private var webRtc: SecureWebRtcPeer? = null
    private lateinit var secureSession: SecureLinkCloudflareSession

    init {
        secureSession = SecureLinkCloudflareSession.create(
            baseWssUrl, roomId, role, handshake,
            object : SecureLinkCloudflareSession.Listener {
                override fun onPeerVerificationRequired(peerId: String, shortAuthenticationString: String) {
                    listener.onPeerVerificationRequired(peerId, shortAuthenticationString)
                }

                override fun onSecureReady(peerId: String) {
                    try {
                        require(peerId != localPeerId) { "A device cannot connect to itself" }
                        listener.onSecureSignalingReady(peerId)
                        val rtc = SecureWebRtcPeer(
                            appContext,
                            secureSession,
                            localPeerId,
                            peerId,
                            if (role == CloudflareSignalingClient.Role.CONTROLLER)
                                SecureWebRtcPeer.Role.CONTROLLER else SecureWebRtcPeer.Role.TARGET,
                            turnConfig,
                            object : SecureWebRtcPeer.Listener {
                                override fun onConnecting() = listener.onWebRtcConnecting()
                                override fun onConnected() = listener.onWebRtcConnected()
                                override fun onData(payload: ByteArray) {
                                    try {
                                        require(payload.size <= RemoteMessage.MAX_MESSAGE_BYTES) { "Secure application message exceeds protocol limit" }
                                        val message = RemoteMessage.parse(payload.toString(Charsets.UTF_8))
                                        require(message.sessionId == sessionId) { "Message belongs to another session" }
                                        val now = System.currentTimeMillis()
                                        require(message.sentAt >= now - MAX_CLOCK_SKEW_MS && message.sentAt <= now + MAX_CLOCK_SKEW_MS) {
                                            "Message timestamp is outside the accepted window"
                                        }
                                        val previous = inboundSequence.get()
                                        require(message.sequence > previous) { "Replayed or out-of-order message sequence" }
                                        synchronized(seenMessageIds) {
                                            require(!seenMessageIds.containsKey(message.messageId)) { "Duplicate message ID" }
                                            seenMessageIds[message.messageId] = true
                                        }
                                        require(inboundSequence.compareAndSet(previous, message.sequence)) { "Concurrent or out-of-order inbound message" }
                                        listener.onData(payload)
                                        listener.onRemoteMessage(message)
                                    } catch (error: Throwable) {
                                        listener.onFailure(error)
                                        close()
                                    }
                                }
                                override fun onDisconnected(reason: String) = listener.onDisconnected(reason)
                                override fun onFailure(error: Throwable) = listener.onFailure(error)
                            }
                        )
                        synchronized(lock) {
                            if (closed.get()) {
                                rtc.close()
                                return
                            }
                            webRtc = rtc
                            while (pendingSignals.isNotEmpty()) {
                                rtc.acceptSecureSignal(pendingSignals.removeFirst())
                            }
                        }
                        rtc.start()
                    } catch (error: Throwable) {
                        listener.onFailure(error)
                        close()
                    }
                }

                override fun onApplicationFrame(payload: ByteArray) {
                    synchronized(lock) {
                        if (closed.get()) return
                        val rtc = webRtc
                        if (rtc != null) {
                            rtc.acceptSecureSignal(payload)
                        } else {
                            if (pendingSignals.size >= MAX_PENDING_SIGNALS) {
                                listener.onFailure(IllegalStateException("Too many early encrypted WebRTC signals"))
                                close()
                            } else {
                                pendingSignals.addLast(payload.copyOf())
                            }
                        }
                    }
                }

                override fun onRoomEvent(type: String, payload: JSONObject) =
                    listener.onRoomEvent(type, payload)

                override fun onDisconnected(reason: String) {
                    listener.onDisconnected(reason)
                    close()
                }

                override fun onFailure(error: Throwable) {
                    listener.onFailure(error)
                    close()
                }
            }
        )
    }

    fun connect() {
        check(!closed.get()) { "Secure internet session is closed" }
        secureSession.connect()
    }

    /** Call only after the owner compares the SAS and peer ID on both devices. */
    fun approvePeer(shortAuthenticationString: String, expectedPeerId: String) {
        check(!closed.get()) { "Secure internet session is closed" }
        secureSession.approvePeer(shortAuthenticationString, expectedPeerId)
    }

    /** Send a bounded, versioned application message over the DTLS DataChannel. */
    fun sendMessage(type: String, payload: JSONObject = JSONObject()): Boolean {
        if (closed.get()) return false
        return runCatching {
            val sequence = outboundSequence.getAndIncrement()
            require(sequence >= 0) { "Outbound sequence exhausted" }
            val message = RemoteMessage(
                type = type,
                sessionId = sessionId,
                messageId = UUID.randomUUID().toString(),
                sequence = sequence,
                sentAt = System.currentTimeMillis(),
                payload = payload
            )
            val bytes = message.toJson().toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= RemoteMessage.MAX_MESSAGE_BYTES) { "Application message exceeds protocol limit" }
            RemoteMessage.parse(bytes.toString(Charsets.UTF_8))
            send(bytes)
        }.getOrElse {
            listener.onFailure(it)
            false
        }
    }

    /** Application data is sent only over the DTLS-protected WebRTC DataChannel. */
    fun send(payload: ByteArray): Boolean {
        if (closed.get()) return false
        return webRtc?.send(payload) ?: false
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            pendingSignals.clear()
            synchronized(seenMessageIds) { seenMessageIds.clear() }
            webRtc?.close()
            webRtc = null
        }
        secureSession.close()
    }

    companion object {
        private const val MAX_PENDING_SIGNALS = 32
        private const val MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L
    }
}
