package com.rkremote.remote

import android.content.Context
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end encrypted Cloudflare relay:
 * WSS -> Noise XX + mutual owner approval -> compressed, fragmented Noise application frames.
 *
 * Cloudflare forwards opaque Noise ciphertext only. There is no WebRTC, STUN,
 * TURN, SDP, ICE, or plaintext fallback in this transport.
 */
@RequiresApi(33)
class SecureInternetPeer(
    @Suppress("UNUSED_PARAMETER") context: Context,
    baseWssUrl: String,
    roomId: String,
    private val role: CloudflareSignalingClient.Role,
    handshake: SecureLinkHandshake,
    identityStore: P2pIdentityStore,
    private val commandRouter: SecureRemoteCommandRouter? = null,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onRelayConnected() {}
        fun onPeerVerificationRequired(peerId: String, shortAuthenticationString: String)
        fun onSecureSignalingReady(peerId: String)
        fun onWebRtcConnecting()
        fun onWebRtcConnected()
        fun onData(payload: ByteArray)
        fun onRemoteMessage(message: RemoteMessage) {}
        fun onCommandResult(message: RemoteMessage, result: SecureRemoteCommandRouter.Result) {}
        fun onRoomEvent(type: String, payload: JSONObject)
        fun onDisconnected(reason: String)
        fun onFailure(error: Throwable)
    }

    private val localPeerId = identityStore.getOrCreateIdentity().peerId
    private val closed = AtomicBoolean(false)
    private val outboundSequence = AtomicLong(0)
    private val sessionId = MessageDigest.getInstance("SHA-256")
        .digest(roomId.toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }
    private val messageGuard = SecureMessageGuard(sessionId)
    private val outboundLock = Any()
    private val relayCodec = SecureRelayFrameCodec()
    private val heartbeatStarted = AtomicBoolean(false)
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "RKRemote-relay-heartbeat").apply { isDaemon = true }
    }
    @Volatile private var authenticatedPeerId: String? = null
    private lateinit var secureSession: SecureLinkCloudflareSession

    init {
        secureSession = SecureLinkCloudflareSession.create(
            baseWssUrl, roomId, role, handshake,
            object : SecureLinkCloudflareSession.Listener {
                override fun onRelayConnected() = listener.onRelayConnected()

                override fun onPeerVerificationRequired(peerId: String, shortAuthenticationString: String) {
                    listener.onPeerVerificationRequired(peerId, shortAuthenticationString)
                }

                override fun onSecureReady(peerId: String) {
                    try {
                        require(peerId != localPeerId) { "A device cannot connect to itself" }
                        authenticatedPeerId = peerId
                        if (heartbeatStarted.compareAndSet(false, true)) {
                            heartbeatExecutor.scheduleAtFixedRate({
                                if (!closed.get() && !secureSession.sendHeartbeat()) {
                                    try {
                                        listener.onDisconnected("Encrypted Cloudflare relay heartbeat failed")
                                    } finally {
                                        close()
                                    }
                                }
                            }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS)
                        }
                        listener.onSecureSignalingReady(peerId)
                        listener.onWebRtcConnecting()
                        // This callback name is retained for UI compatibility; it now means
                        // the authenticated Cloudflare encrypted relay is ready, not WebRTC.
                        listener.onWebRtcConnected()
                    } catch (error: Throwable) {
                        listener.onFailure(error)
                        close()
                    }
                }

                override fun onApplicationFrame(payload: ByteArray) {
                    try {
                        val messageBytes = relayCodec.accept(payload) ?: return
                        require(messageBytes.size <= RemoteMessage.MAX_MESSAGE_BYTES) {
                            "Secure application message exceeds protocol limit"
                        }
                        val message = RemoteMessage.parse(messageBytes.toString(Charsets.UTF_8))
                        messageGuard.accept(message, System.currentTimeMillis())
                        listener.onData(messageBytes)
                        if (role == CloudflareSignalingClient.Role.TARGET &&
                            SecureRemoteCommandRouter.isCommandType(message.type)) {
                            val peerId = authenticatedPeerId
                                ?: error("Authenticated peer identity is not ready")
                            val result = commandRouter?.dispatch(peerId, message)
                                ?: SecureRemoteCommandRouter.Result(
                                    false,
                                    "Target command router is not configured"
                                )
                            listener.onCommandResult(message, result)
                            if (message.type != "file.transfer.chunk" || !result.accepted) {
                                sendMessage(
                                    "command.result",
                                    JSONObject()
                                        .put("requestId", message.payload.optString("requestId"))
                                        .put(
                                            "transferId",
                                            message.payload.optString(
                                                "transferId",
                                                message.payload.optString("requestId")
                                            )
                                        )
                                        .put("commandType", message.type)
                                        .put("accepted", result.accepted)
                                        .put("reason", result.reason)
                                        .put("responseType", result.responseType ?: JSONObject.NULL)
                                )
                            }
                        }
                        listener.onRemoteMessage(message)
                    } catch (error: Throwable) {
                        listener.onFailure(error)
                        close()
                    }
                }

                override fun onRoomEvent(type: String, payload: JSONObject) {
                    listener.onRoomEvent(type, payload)
                }

                override fun onDisconnected(reason: String) {
                    try {
                        listener.onDisconnected(reason)
                    } finally {
                        close()
                    }
                }

                override fun onFailure(error: Throwable) {
                    try {
                        listener.onFailure(error)
                    } finally {
                        close()
                    }
                }
            }
        )
    }

    fun connect() {
        check(!closed.get()) { "Secure internet session is closed" }
        secureSession.connect()
    }

    /** WebSocket reconnect is not silently attempted; a fresh authenticated session is required. */
    fun requestNetworkRecovery(): Boolean = false

    /** Call only after the owner compares the SAS and peer ID on both devices. */
    fun approvePeer(shortAuthenticationString: String, expectedPeerId: String) {
        check(!closed.get()) { "Secure internet session is closed" }
        secureSession.approvePeer(shortAuthenticationString, expectedPeerId)
    }

    fun activateOwnerApprovedCommands(capabilities: Set<RemoteCapability>) {
        check(!closed.get()) { "Secure internet session is closed" }
        check(role == CloudflareSignalingClient.Role.TARGET) {
            "Only the target device can grant remote command capabilities"
        }
        val peerId = authenticatedPeerId ?: error("No authenticated peer is connected")
        require(capabilities.isNotEmpty()) { "At least one explicit capability grant is required" }
        val router = commandRouter ?: error("No target command router is configured")
        router.beginOwnerApprovedSession(peerId, capabilities)
    }

    fun grantOwnerApprovedCapabilities(capabilities: Set<RemoteCapability>) {
        check(!closed.get()) { "Secure internet session is closed" }
        check(role == CloudflareSignalingClient.Role.TARGET) {
            "Only the target device can grant remote command capabilities"
        }
        require(capabilities.isNotEmpty())
        val peerId = authenticatedPeerId ?: error("No authenticated peer is connected")
        val router = commandRouter ?: error("No target command router is configured")
        if (router.hasActiveSession()) router.grantCapabilities(capabilities)
        else router.beginOwnerApprovedSession(peerId, capabilities)
    }

    fun revokeOwnerApprovedCapabilities(capabilities: Set<RemoteCapability>) {
        check(!closed.get()) { "Secure internet session is closed" }
        check(role == CloudflareSignalingClient.Role.TARGET) {
            "Only the target device can revoke remote command capabilities"
        }
        commandRouter?.revokeCapabilities(capabilities)
    }

    fun authenticatedPeerId(): String? = authenticatedPeerId

    fun hasOwnerApprovedCapability(capability: RemoteCapability): Boolean {
        val peerId = authenticatedPeerId ?: return false
        return commandRouter?.hasCapability(peerId, capability) == true
    }

    fun revokeOwnerApprovedCommands() {
        commandRouter?.endSession()
    }

    /** Create a bounded application message and send it as encrypted relay fragments. */
    fun sendMessage(type: String, payload: JSONObject = JSONObject()): Boolean =
        synchronized(outboundLock) {
            if (closed.get()) return@synchronized false
            runCatching {
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
                require(bytes.size <= RemoteMessage.MAX_MESSAGE_BYTES) {
                    "Application message exceeds protocol limit"
                }
                RemoteMessage.parse(bytes.toString(Charsets.UTF_8))
                send(bytes)
            }.getOrElse {
                listener.onFailure(it)
                false
            }
        }

    /** All app payloads are compressed when useful, fragmented, and Noise-encrypted before WSS. */
    fun send(payload: ByteArray): Boolean = synchronized(outboundLock) {
        if (closed.get() || payload.isEmpty()) return@synchronized false
        runCatching {
            val messageType = runCatching {
                JSONObject(payload.toString(Charsets.UTF_8)).optString("type")
            }.getOrDefault("")
            val allowCompression = !messageType.startsWith("screen.") &&
                !messageType.startsWith("camera.")
            relayCodec.encode(payload, allowCompression).all { fragment ->
                secureSession.sendApplication(fragment).also { accepted ->
                    check(accepted) { "Cloudflare encrypted relay rejected an application frame" }
                }
            }
        }.getOrElse {
            listener.onFailure(it)
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            heartbeatExecutor.shutdownNow()
            relayCodec.clear()
            commandRouter?.endSession()
        } finally {
            authenticatedPeerId = null
            secureSession.close()
        }
    }
}
