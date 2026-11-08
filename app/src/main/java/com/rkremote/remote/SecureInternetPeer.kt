package com.rkremote.remote

import android.content.Context
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import java.util.UUID

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
    private val commandRouter: SecureRemoteCommandRouter? = null,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
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

    private val appContext = context.applicationContext
    private val localPeerId = identityStore.getOrCreateIdentity().peerId
    private val turnConfig = turnServers.toList()
    private val closed = AtomicBoolean(false)
    private val outboundSequence = AtomicLong(0)
    private val sessionId = MessageDigest.getInstance("SHA-256")
        .digest(roomId.toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }
    private val messageGuard = SecureMessageGuard(sessionId)
    private val pendingSignals = ArrayDeque<ByteArray>()
    private val lock = Any()
    private val outboundLock = Any()
    private var webRtc: SecureWebRtcPeer? = null
    @Volatile private var authenticatedPeerId: String? = null
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
                        authenticatedPeerId = peerId
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
                                        messageGuard.accept(message, System.currentTimeMillis())
                                        listener.onData(payload)
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
                                            sendMessage(
                                                "command.result",
                                                JSONObject()
                                                    .put("requestId", message.payload.optString("requestId"))
                                                    .put("commandType", message.type)
                                                    .put("accepted", result.accepted)
                                                    .put("reason", result.reason)
                                                    .put("responseType", result.responseType ?: JSONObject.NULL)
                                            )
                                        }
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

    /**
     * Explicitly activate the target-side command allow-list after local owner
     * approval. Completing the Noise handshake alone grants no commands.
     */
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

    /** Add explicit owner-approved capabilities without resetting replay state. */
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

    /** Revoke every remote command grant without closing the encrypted link. */
    fun revokeOwnerApprovedCommands() {
        commandRouter?.endSession()
    }

    /** Send a bounded, versioned application message over the DTLS DataChannel. */
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
            webRtc?.close()
            webRtc = null
        }
        commandRouter?.endSession()
        authenticatedPeerId = null
        secureSession.close()
    }

    companion object {
        private const val MAX_PENDING_SIGNALS = 32
    }
}
