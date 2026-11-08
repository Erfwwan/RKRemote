package com.rkremote.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Cloudflare is used only as an ephemeral WebRTC signaling rendezvous.
 * It must never carry RKRemote commands, files, notifications, or media.
 * Signaling messages are not yet authenticated end-to-end by this class.
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
        fun onSignal(type: String, payload: JSONObject)
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

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                    webSocket.close(1009, "Message too large")
                    listener.onFailure(IllegalArgumentException("Signaling message too large"))
                    return
                }
                runCatching {
                    val message = JSONObject(text)
                    require(message.optInt("v") == 1) { "Unsupported signaling version" }
                    val type = message.optString("type")
                    require(type in ALLOWED_TYPES) { "Unsupported signaling message" }
                    val payload = message.optJSONObject("payload") ?: JSONObject()
                    if (type == "peer-joined" || type == "peer-left" || type == "peer-not-ready") {
                        listener.onSignal(type, payload)
                    } else {
                        listener.onSignal(type, payload)
                    }
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

    fun send(type: String, payload: JSONObject = JSONObject()): Boolean {
        require(type in ALLOWED_TYPES) { "Unsupported signaling message" }
        val text = JSONObject().put("v", 1).put("type", type).put("payload", payload).toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) { "Signaling message too large" }
        return socket?.send(text) ?: false
    }

    @Synchronized
    fun close() {
        socket?.close(1000, "Owner ended signaling")
        socket = null
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 12 * 1024
        private val ALLOWED_TYPES = setOf("offer", "answer", "ice", "ready", "reject", "bye")

        /** Generate a private, unguessable room identifier. Share it only with the intended peer. */
        fun newRoomId(): String = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
    }
}
