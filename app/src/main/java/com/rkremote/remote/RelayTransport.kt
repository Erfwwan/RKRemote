package com.rkremote.remote

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * TLS WebSocket transport. A SHA-256 certificate/public-key pin is mandatory.
 * It transports protocol envelopes only; privileged Android actions must be
 * separately authorized by the agent and are not executed in this class.
 */
class RelayTransport(
    private val endpoint: String,
    private val sha256Pin: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onTextMessage(message: String)
        fun onDisconnected(reason: String)
        fun onFailure(error: Throwable)
    }

    private val uri = URI(endpoint)
    private val host = uri.host ?: throw IllegalArgumentException("Relay URL has no host")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .certificatePinner(
            CertificatePinner.Builder().add(host, sha256Pin).build()
        )
        .build()

    @Volatile private var socket: WebSocket? = null

    init {
        require(uri.scheme.equals("wss", ignoreCase = true)) {
            "Only wss:// relay endpoints are accepted"
        }
        require(sha256Pin.startsWith("sha256/")) {
            "A SHA-256 certificate pin is required"
        }
    }

    @Synchronized
    fun connect() {
        check(socket == null) { "Transport is already connecting or connected" }
        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray(Charsets.UTF_8).size > RemoteMessage.MAX_MESSAGE_BYTES) {
                    webSocket.close(1009, "Message too large")
                    listener.onFailure(IllegalArgumentException("Relay message too large"))
                } else {
                    listener.onTextMessage(text)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@RelayTransport) { socket = null }
                listener.onDisconnected(reason.ifBlank { "Closed ($code)" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@RelayTransport) { socket = null }
                listener.onFailure(t)
            }
        })
    }

    fun send(message: RemoteMessage): Boolean =
        socket?.send(message.toJson().toString()) ?: false

    @Synchronized
    fun close() {
        socket?.close(1000, "User ended session")
        socket = null
    }
}
