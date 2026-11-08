package com.rkremote.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Opaque binary rendezvous transport for the RK-SecureLink Noise handshake.
 * This class does not implement cryptography: callers must pass only Noise
 * handshake/transport frames produced and authenticated by the secure-session layer.
 * Server text messages are restricted to non-sensitive room lifecycle events.
 */
class CloudflareSignalingClient(
    private val baseWssUrl: String,
    private val roomId: String,
    private val role: Role,
    private val listener: Listener
) {
    enum class Role(val wireValue: String) { TARGET("target"), CONTROLLER("controller") }

    interface Listener {
        fun onConnected()
        fun onEncryptedFrame(frame: ByteArray)
        fun onRoomEvent(type: String, payload: JSONObject)
        fun onDisconnected(reason: String)
        fun onFailure(error: Throwable)
    }

    private val baseUri = URI(baseWssUrl)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null

    init {
        require(baseUri.scheme.equals("wss", ignoreCase = true)) { "Only wss:// is accepted" }
        require(baseUri.host != null && baseUri.rawQuery == null && baseUri.rawFragment == null) {
            "Invalid Cloudflare signaling URL"
        }
        require(roomId.matches(Regex("[a-f0-9]{64}"))) { "Room ID must be 256-bit lowercase hex" }
    }

    @Synchronized
    fun connect() {
        check(socket == null) { "Signaling socket is already active" }
        val endpoint = baseWssUrl.trimEnd('/') + "/signal/" + roomId + "?role=" + role.wireValue
        socket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size == 0 || bytes.size > MAX_FRAME_BYTES) {
                    webSocket.close(1009, "Invalid encrypted frame size")
                    listener.onFailure(IllegalArgumentException("Encrypted signaling frame size is invalid"))
                    return
                }
                listener.onEncryptedFrame(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray(Charsets.UTF_8).size > MAX_CONTROL_BYTES) {
                    webSocket.close(1009, "Control message too large")
                    listener.onFailure(IllegalArgumentException("Room control message too large"))
                    return
                }
                runCatching {
                    val message = JSONObject(text)
                    require(message.optInt("v") == 1) { "Unsupported room-control version" }
                    val type = message.optString("type")
                    require(type in ROOM_EVENTS) { "Unexpected text message from signaling server" }
                    listener.onRoomEvent(type, message.optJSONObject("payload") ?: JSONObject())
                }.onFailure(listener::onFailure)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@CloudflareSignalingClient) { socket = null }
                listener.onDisconnected(reason.ifBlank { "Closed ($code)" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@CloudflareSignalingClient) { socket = null }
                listener.onFailure(t)
            }
        })
    }

    /** Send one complete opaque Noise frame. Never pass plaintext SDP, ICE, or app data. */
    fun sendEncryptedFrame(frame: ByteArray): Boolean {
        require(frame.isNotEmpty() && frame.size <= MAX_FRAME_BYTES) { "Invalid encrypted frame size" }
        return socket?.send(ByteString.of(*frame)) ?: false
    }

    @Synchronized
    fun close() {
        socket?.close(1000, "Owner ended signaling")
        socket = null
    }

    companion object {
        private const val MAX_FRAME_BYTES = 12 * 1024
        private const val MAX_CONTROL_BYTES = 1024
        private val ROOM_EVENTS = setOf("peer-joined", "peer-left", "peer-not-ready")

        /** Generate a private, unguessable room identifier. Share it only with the intended peer. */
        fun newRoomId(): String = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
    }
}
