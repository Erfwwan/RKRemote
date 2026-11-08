package com.rkremote.remote

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal read-only LAN agent. It deliberately exposes only health and the
 * owner-selected SAF folder listing. It is not an internet-facing server.
 */
class LanAgentServer(
    private val context: Context,
    private val token: String,
    private val port: Int = DEFAULT_PORT
) {
    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        check(token.length >= 24) { "Session token is too short" }
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            try {
                ServerSocket(port, 8).use { socketServer ->
                    server = socketServer
                    while (running.get()) {
                        try {
                            val client = socketServer.accept()
                            Thread { handle(client) }.start()
                        } catch (e: SocketException) {
                            if (running.get()) throw e
                        }
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            } finally {
                server = null
            }
        }, "RKRemote-LAN-Agent").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        worker?.interrupt()
        worker = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                respond(client.getOutputStream(), 405, JSONObject().put("error", "GET only"))
                return
            }
            val expected = "Bearer $token".toByteArray(Charsets.UTF_8)
            val supplied = (headers["authorization"] ?: "").toByteArray(Charsets.UTF_8)
            if (!MessageDigest.isEqual(expected, supplied)) {
                respond(client.getOutputStream(), 401, JSONObject().put("error", "Unauthorized"))
                return
            }
            val endpoint = parts[1].substringBefore('?')
            if (endpoint == "/screen.jpg") {
                val frame = ScreenFrameStore.latestJpeg
                if (frame == null) {
                    respond(client.getOutputStream(), 503, JSONObject().put("error", "Screen sharing is not active or no frame is ready"))
                } else {
                    respondBytes(client.getOutputStream(), frame)
                }
                return
            }
            val response = when (endpoint) {
                "/health" -> JSONObject()
                    .put("app", "RKRemote")
                    .put("status", "online")
                    .put("mode", "read-only-lan")
                "/files" -> listSelectedFolder()
                else -> {
                    respond(client.getOutputStream(), 404, JSONObject().put("error", "Not found"))
                    return
                }
            }
            respond(client.getOutputStream(), 200, response)
        }
    }

    private fun listSelectedFolder(): JSONObject {
        val raw = context.getSharedPreferences("rkremote_setup", Context.MODE_PRIVATE)
            .getString("selected_tree_uri", null)
            ?: return JSONObject().put("error", "No owner-selected folder").put("items", JSONArray())
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(raw))
            ?: return JSONObject().put("error", "Folder unavailable").put("items", JSONArray())
        if (!tree.canRead()) return JSONObject().put("error", "Folder permission unavailable")
        val items = JSONArray()
        val children = tree.listFiles()
        children.take(MAX_LIST_ITEMS).forEach { file ->
            items.put(JSONObject()
                .put("name", file.name ?: "unnamed")
                .put("directory", file.isDirectory)
                .put("size", if (file.isFile) file.length() else JSONObject.NULL)
                .put("mimeType", file.type ?: JSONObject.NULL))
        }
        return JSONObject().put("items", items).put("truncated", children.size > MAX_LIST_ITEMS)
    }

    private fun respondBytes(output: OutputStream, bytes: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun respond(output: OutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val label = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Method Not Allowed"
        }
        val header = "HTTP/1.1 $status $label\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    companion object {
        const val DEFAULT_PORT = 18765
        private const val MAX_LIST_ITEMS = 200
    }
}
