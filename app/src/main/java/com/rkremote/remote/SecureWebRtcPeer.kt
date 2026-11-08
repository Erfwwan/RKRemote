package com.rkremote.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct WebRTC data transport layered on mutually-approved RK-SecureLink.
 * SDP and ICE are carried only as Noise-encrypted application frames. The SDP
 * fingerprint is therefore authenticated by the approved peer identity, and
 * libwebrtc validates the DTLS certificate against that fingerprint.
 */
class SecureWebRtcPeer(
    context: Context,
    private val secureLink: SecureLinkCloudflareSession,
    private val localPeerId: String,
    private val authenticatedPeerId: String,
    private val role: Role,
    turnServers: List<TurnServer> = emptyList(),
    private val listener: Listener
) : AutoCloseable {

    enum class Role { CONTROLLER, TARGET }

    data class TurnServer(
        val urls: List<String>,
        val username: String,
        val credential: String
    ) {
        init {
            require(urls.isNotEmpty())
            require(username.isNotBlank() && credential.isNotBlank())
            require(urls.all { it.startsWith("turn:") || it.startsWith("turns:") })
        }
    }

    interface Listener {
        fun onConnecting()
        fun onConnected()
        fun onData(payload: ByteArray)
        fun onDisconnected(reason: String)
        fun onFailure(error: Throwable)
    }

    private val appContext = context.applicationContext
    private val turnConfig = turnServers.toList()
    private val closed = AtomicBoolean(false)
    private val remoteDescriptionSet = AtomicBoolean(false)
    private val pendingRemoteIce = ArrayDeque<IceCandidate>()
    private val dataFramer = SecureDataChannelFramer()
    private val lock = Any()
    private val sendLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val disconnectTimeout = Runnable {
        if (!closed.get()) {
            listener.onDisconnected("WebRTC connectivity did not recover within the grace period")
        }
    }

    private fun scheduleDisconnectGracePeriod() {
        if (closed.get()) return
        mainHandler.removeCallbacks(disconnectTimeout)
        mainHandler.postDelayed(disconnectTimeout, DISCONNECT_GRACE_PERIOD_MS)
    }

    private fun clearDisconnectGracePeriod() {
        mainHandler.removeCallbacks(disconnectTimeout)
    }
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (closed.get()) return
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    clearDisconnectGracePeriod()
                    if (dataChannel?.state() == DataChannel.State.OPEN) listener.onConnected()
                }
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    scheduleDisconnectGracePeriod()
                PeerConnection.IceConnectionState.FAILED -> {
                    listener.onFailure(IllegalStateException("WebRTC ICE connectivity failed"))
                    close()
                }
                PeerConnection.IceConnectionState.CLOSED -> Unit
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            runCatching {
                sendSignal(JSONObject()
                    .put("type", "ice")
                    .put("peerId", localPeerId)
                    .put("mid", candidate.sdpMid)
                    .put("mLine", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp))
            }.onFailure {
                listener.onFailure(it)
                close()
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) {
            if (role != Role.TARGET || channel.label() != DATA_CHANNEL_LABEL || dataChannel != null) {
                channel.close()
                channel.dispose()
                return
            }
            dataChannel = channel
            observeDataChannel(channel)
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<org.webrtc.MediaStream>) = Unit
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (closed.get()) return
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> clearDisconnectGracePeriod()
                PeerConnection.PeerConnectionState.DISCONNECTED -> scheduleDisconnectGracePeriod()
                PeerConnection.PeerConnectionState.FAILED -> {
                    listener.onFailure(IllegalStateException("WebRTC peer connection failed"))
                    close()
                }
                PeerConnection.PeerConnectionState.CLOSED -> {
                    listener.onDisconnected("WebRTC peer connection closed")
                }
                else -> Unit
            }
        }
    }

    init {
        require(localPeerId.matches(Regex("[a-f0-9]{64}"))) {
            "Local peer ID must be a SHA-256 hex identity"
        }
        require(authenticatedPeerId.matches(Regex("[a-f0-9]{64}")) && localPeerId != authenticatedPeerId) {
            "Authenticated remote peer ID is invalid"
        }
        initializeFactory(appContext)
        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        turnConfig.forEach { server ->
            iceServers += PeerConnection.IceServer.builder(server.urls)
                .setUsername(server.username)
                .setPassword(server.credential)
                .createIceServer()
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        peerConnection = factory!!.createPeerConnection(rtcConfig, observer)
            ?: error("WebRTC could not create a PeerConnection")
    }

    /** Call only after SecureLink reports mutual owner approval and onSecureReady. */
    fun start() {
        check(!closed.get()) { "WebRTC peer is closed" }
        listener.onConnecting()
        if (role == Role.CONTROLLER) {
            val init = DataChannel.Init().apply {
                ordered = true
                negotiated = false
            }
            dataChannel = peerConnection!!.createDataChannel(DATA_CHANNEL_LABEL, init)
                ?: error("Could not create WebRTC data channel")
            observeDataChannel(dataChannel!!)
            peerConnection!!.createOffer(sdpObserver(onCreate = { description ->
                setLocalAndSignal(description, "offer")
            }), MediaConstraints())
        }
        // Target waits for the encrypted offer and incoming DataChannel.
    }

    fun send(payload: ByteArray): Boolean {
        if (closed.get() || payload.isEmpty() || payload.size > MAX_APP_MESSAGE_BYTES) return false
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return runCatching {
            val frames = dataFramer.fragment(payload)
            val totalFrameBytes = frames.sumOf { it.size.toLong() }
            synchronized(sendLock) {
                if (closed.get() || channel.state() != DataChannel.State.OPEN) {
                    return@synchronized false
                }
                // Reserve room for the entire logical message before sending its first
                // fragment. This avoids knowingly emitting a partial RKD1 message when
                // the channel is already congested.
                if (totalFrameBytes > MAX_BUFFERED_BYTES ||
                    channel.bufferedAmount() > MAX_BUFFERED_BYTES - totalFrameBytes) {
                    return@synchronized false
                }
                for (frame in frames) {
                    if (closed.get() || channel.state() != DataChannel.State.OPEN) {
                        listener.onFailure(IllegalStateException("DataChannel closed during fragmented send"))
                        close()
                        return@synchronized false
                    }
                    if (!channel.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))) {
                        listener.onFailure(IllegalStateException("DataChannel rejected a reserved fragment"))
                        close()
                        return@synchronized false
                    }
                }
                true
            }
        }.getOrElse {
            listener.onFailure(it)
            close()
            false
        }
    }

    private fun observeDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (closed.get()) return
                when (channel.state()) {
                    DataChannel.State.OPEN -> {
                        if (peerConnection?.iceConnectionState() in setOf(
                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED
                            )) {
                            clearDisconnectGracePeriod()
                            listener.onConnected()
                        }
                    }
                    DataChannel.State.CLOSED ->
                        listener.onDisconnected("WebRTC DataChannel closed")
                    else -> Unit
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary || buffer.data.remaining() !in (HEADER_BYTES + 1)..MAX_DATA_FRAME_BYTES) {
                    listener.onFailure(IllegalArgumentException("Invalid WebRTC data-channel frame"))
                    close()
                    return
                }
                runCatching {
                    val frame = ByteArray(buffer.data.remaining())
                    buffer.data.get(frame)
                    dataFramer.accept(frame)?.let(listener::onData)
                }.onFailure {
                    listener.onFailure(it)
                    close()
                }
            }
        })
    }

    /** Forward only SecureLink onApplicationFrame payloads to this method. */
    fun acceptSecureSignal(payload: ByteArray) {
        if (closed.get() || payload.isEmpty() || payload.size > MAX_SIGNAL_BYTES) {
            listener.onFailure(IllegalArgumentException("Invalid encrypted WebRTC signaling frame"))
            close()
            return
        }
        try {
            val message = JSONObject(payload.toString(Charsets.UTF_8))
            require(message.getString("peerId") == authenticatedPeerId) {
                "Signaling message peer identity mismatch"
            }
            when (message.getString("type")) {
                "offer" -> {
                    require(role == Role.TARGET) { "Controller cannot accept a remote offer" }
                    val sdp = message.getString("sdp")
                    requireFingerprintMatches(sdp, message.getString("fingerprint"))
                    peerConnection!!.setRemoteDescription(
                        sdpObserver(onSet = {
                            remoteDescriptionSet.set(true)
                            flushRemoteIce()
                            peerConnection!!.createAnswer(sdpObserver(onCreate = { answer ->
                                setLocalAndSignal(answer, "answer")
                            }), MediaConstraints())
                        }),
                        SessionDescription(SessionDescription.Type.OFFER, sdp)
                    )
                }
                "answer" -> {
                    require(role == Role.CONTROLLER) { "Target cannot accept an answer" }
                    val sdp = message.getString("sdp")
                    requireFingerprintMatches(sdp, message.getString("fingerprint"))
                    peerConnection!!.setRemoteDescription(
                        sdpObserver(onSet = {
                            remoteDescriptionSet.set(true)
                            flushRemoteIce()
                        }),
                        SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    )
                }
                "ice" -> {
                    val candidate = IceCandidate(
                        message.getString("mid"),
                        message.getInt("mLine"),
                        message.getString("candidate")
                    )
                    synchronized(lock) {
                        if (remoteDescriptionSet.get()) {
                            require(peerConnection!!.addIceCandidate(candidate)) {
                                "WebRTC rejected remote ICE candidate"
                            }
                        } else {
                            require(pendingRemoteIce.size < MAX_PENDING_ICE) {
                                "Too many ICE candidates arrived before remote SDP"
                            }
                            pendingRemoteIce.addLast(candidate)
                        }
                    }
                }
                else -> error("Unknown encrypted WebRTC signaling message")
            }
        } catch (error: Throwable) {
            listener.onFailure(error)
            close()
        }
    }

    private fun flushRemoteIce() {
        synchronized(lock) {
            while (pendingRemoteIce.isNotEmpty()) {
                val candidate = pendingRemoteIce.removeFirst()
                require(peerConnection!!.addIceCandidate(candidate)) {
                    "WebRTC rejected queued ICE candidate"
                }
            }
        }
    }

    private fun setLocalAndSignal(description: SessionDescription, type: String) {
        val fingerprint = DtlsFingerprint.extractSha256(description.description)
        peerConnection!!.setLocalDescription(
            sdpObserver(onSet = {
                sendSignal(JSONObject()
                    .put("type", type)
                    .put("peerId", localPeerId)
                    .put("fingerprint", fingerprint)
                    .put("sdp", description.description))
            }),
            description
        )
    }

    private fun sendSignal(message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SIGNAL_BYTES) { "WebRTC signaling description exceeds secure tunnel limit" }
        check(secureLink.sendApplication(bytes)) {
            "SecureLink is not ready; refusing plaintext signaling fallback"
        }
    }

    private fun requireFingerprintMatches(sdp: String, envelopeFingerprint: String) {
        DtlsFingerprint.requireMatches(sdp, envelopeFingerprint)
    }

    private fun guarded(block: () -> Unit) {
        try {
            if (!closed.get()) block()
        } catch (error: Throwable) {
            listener.onFailure(error)
            close()
        }
    }

    private fun sdpObserver(
        onCreate: (SessionDescription) -> Unit = {},
        onSet: () -> Unit = {}
    ): SdpObserver =
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = guarded { onCreate(description) }
            override fun onSetSuccess() = guarded { onSet() }
            override fun onCreateFailure(error: String) {
                listener.onFailure(IllegalStateException("WebRTC SDP creation failed: $error"))
                close()
            }
            override fun onSetFailure(error: String) {
                listener.onFailure(IllegalStateException("WebRTC SDP setup failed: $error"))
                close()
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clearDisconnectGracePeriod()
        synchronized(lock) { pendingRemoteIce.clear() }
        dataFramer.clear()

        // Disposal must be best-effort per resource: one native cleanup failure
        // must not prevent releasing the remaining WebRTC objects.
        val channel = dataChannel
        dataChannel = null
        if (channel != null) {
            runCatching { channel.unregisterObserver() }
            runCatching { channel.close() }
            runCatching { channel.dispose() }
        }
        val connection = peerConnection
        peerConnection = null
        if (connection != null) {
            runCatching { connection.close() }
            runCatching { connection.dispose() }
        }
    }

    companion object {
        private const val DATA_CHANNEL_LABEL = "rkremote-secure-v1"
        private const val MAX_SIGNAL_BYTES = 10 * 1024
        private const val MAX_DATA_FRAME_BYTES = 12 * 1024
        private const val HEADER_BYTES = 24
        private const val MAX_APP_MESSAGE_BYTES = 4 * 1024 * 1024
        private const val MAX_BUFFERED_BYTES = 2L * 1024L * 1024L
        private const val MAX_PENDING_ICE = 128
        private const val DISCONNECT_GRACE_PERIOD_MS = 12_000L

        @Volatile private var factory: PeerConnectionFactory? = null

        @Synchronized
        private fun initializeFactory(context: Context) {
            if (factory != null) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        }
    }
}
