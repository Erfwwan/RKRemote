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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val closed = AtomicBoolean(false)

    init {
        require(baseUri.scheme?.equals("wss", ignoreCase = true) == true) { "Only wss:// is accepted" }
        require(baseUri.host != null && baseUri.rawQuery == null && baseUri.rawFragment == null) {
            "Invalid Cloudflare signaling URL"
        }
        require(baseUri.rawUserInfo == null) {
            "Signaling URL must not contain embedded credentials"
        }
        require(baseUri.rawPath.isNullOrEmpty() || baseUri.rawPath == "/") {
            "Signaling URL must be the Worker origin without a custom path"
        }
        require(roomId.matches(Regex("[a-f0-9]{64}"))) { "Room ID must be 256-bit lowercase hex" }
    }

    @Synchronized
    fun connect() {
        check(!closed.get()) { "Signaling client is closed" }
        check(socket == null) { "Signaling socket is already active" }
        val endpoint = baseWssUrl.trimEnd('/') + "/signal/" + roomId + "?role=" + role.wireValue
        socket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!closed.get()) listener.onConnected() else webSocket.cancel()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (closed.get()) return
                if (bytes.size == 0 || bytes.size > MAX_FRAME_BYTES) {
                    webSocket.close(1009, "Invalid encrypted frame size")
                    listener.onFailure(IllegalArgumentException("Encrypted signaling frame size is invalid"))
                    return
                }
                listener.onEncryptedFrame(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                runCatching {
                    val message = CloudflareRoomControlMessage.parse(text)
                    listener.onRoomEvent(message.type, message.payload)
                }.onFailure { failure ->
                    webSocket.close(1003, "Invalid room control message")
                    listener.onFailure(failure)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@CloudflareSignalingClient) {
                    if (socket === webSocket) socket = null
                }
                if (!closed.get()) listener.onDisconnected(reason.ifBlank { "Closed ($code)" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@CloudflareSignalingClient) {
                    if (socket === webSocket) socket = null
                }
                if (!closed.get()) listener.onFailure(t)
            }
        })
    }

    /** Send one complete opaque Noise frame. Never pass plaintext SDP, ICE, or app data. */
    fun sendEncryptedFrame(frame: ByteArray): Boolean {
        require(frame.isNotEmpty() && frame.size <= MAX_FRAME_BYTES) { "Invalid encrypted frame size" }
        if (closed.get()) return false
        return socket?.send(ByteString.of(*frame)) ?: false
    }

    @Synchronized
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val activeSocket = socket
        socket = null
        // This client belongs to one terminal session; cancel rather than leaving an
        // asynchronous WebSocket close handshake and dispatcher thread behind.
        activeSocket?.cancel()
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val MAX_FRAME_BYTES = 12 * 1024

        /** Generate a private, unguessable room identifier. Share it only with the intended peer. */
        fun newRoomId(): String = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
    }
}
