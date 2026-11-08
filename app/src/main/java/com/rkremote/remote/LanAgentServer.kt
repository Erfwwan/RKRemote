package com.rkremote.remote

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal LAN agent for a trusted private network. It exposes health, an
 * owner-selected SAF folder listing, screen snapshots, and a single tap command
 * gated by the owner's manually enabled Accessibility service.
 */
class LanAgentServer(
    private val context: Context,
    private val token: String,
    private val port: Int = DEFAULT_PORT
) {
    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    private var worker: Thread? = null
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val clients = ThreadPoolExecutor(
        2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16)
    ) { task, _ ->
        // Saturated server: reject excess clients rather than creating unbounded threads.
        if (task is ClientTask) task.close()
    }

    private inner class ClientTask(private val socket: Socket) : Runnable {
        override fun run() {
            try {
                handle(socket)
            } finally {
                clientSockets.remove(socket)
            }
        }
        fun close() {
            clientSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

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
                            clientSockets.add(client)
                            val task = ClientTask(client)
                            try {
                                clients.execute(task)
                            } catch (_: Exception) {
                                task.close()
                            }
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
        clientSockets.toList().forEach { runCatching { it.close() } }
        clientSockets.clear()
        clients.shutdownNow().forEach { task ->
            if (task is ClientTask) task.close()
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 4000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val requestLine = readAsciiLine(input, MAX_REQUEST_LINE) ?: return
            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            var headerBytes = 0
            while (true) {
                val line = readAsciiLine(input, MAX_HEADER_LINE_BYTES) ?: break
                if (line.isEmpty()) break
                headerCount++
                headerBytes += line.length
                if (headerCount > MAX_HEADERS || headerBytes > MAX_HEADER_BYTES) {
                    respond(output, 400, JSONObject().put("error", "Headers too large"))
                    return
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    val name = line.substring(0, separator).trim().lowercase()
                    if (name in headers) {
                        respond(output, 400, JSONObject().put("error", "Duplicate header"))
                        return
                    }
                    headers[name] = line.substring(separator + 1).trim()
                }
            }
            val parts = requestLine.split(' ')
            if (parts.size != 3 || (parts[0] != "GET" && parts[0] != "POST") ||
                parts[2] != "HTTP/1.1") {
                respond(output, 405, JSONObject().put("error", "GET or POST HTTP/1.1 only"))
                return
            }
            val expected = "Bearer $token".toByteArray(Charsets.UTF_8)
            val supplied = (headers["authorization"] ?: "").toByteArray(Charsets.UTF_8)
            if (!MessageDigest.isEqual(expected, supplied)) {
                respond(output, 401, JSONObject().put("error", "Unauthorized"))
                return
            }
            if (headers.containsKey("transfer-encoding")) {
                respond(output, 400, JSONObject().put("error", "Transfer-Encoding is not supported"))
                return
            }
            val endpoint = parts[1].substringBefore('?')
            if (parts[0] == "POST" && endpoint in setOf("/input/tap", "/input/gesture", "/input/text")) {
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 1..MAX_INPUT_BODY_BYTES) {
                    respond(output, 400, JSONObject().put("error", "Invalid request body size"))
                    return
                }
                val bodyBytes = readExact(input, length)
                    ?: run {
                        respond(output, 400, JSONObject().put("error", "Incomplete request body"))
                        return
                    }
                val command = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
                if (command == null) {
                    respond(output, 400, JSONObject().put("error", "Invalid JSON body"))
                    return
                }
                val accepted: Boolean
                val action: String
                when (endpoint) {
                    "/input/tap", "/input/gesture" -> {
                        val x1 = command.optDouble("x", command.optDouble("startX", Double.NaN))
                        val y1 = command.optDouble("y", command.optDouble("startY", Double.NaN))
                        val x2 = command.optDouble("endX", x1)
                        val y2 = command.optDouble("endY", y1)
                        val duration = command.optLong("durationMs", 80L)
                        if (listOf(x1, y1, x2, y2).any { !it.isFinite() || it !in 0.0..1.0 } ||
                            duration !in 40L..1500L) {
                            respond(output, 400, JSONObject().put("error", "Coordinates must be 0..1 and duration 40..1500 ms"))
                            return
                        }
                        accepted = RemoteAccessibilityService.dispatchGestureNormalized(
                            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration
                        )
                        action = if (duration >= 500L && x1 == x2 && y1 == y2) "long_press" else if (x1 == x2 && y1 == y2) "tap" else "swipe"
                    }
                    else -> {
                        val value = command.optString("text", "")
                        if (value.length > MAX_REMOTE_TEXT_LENGTH) {
                            respond(output, 400, JSONObject().put("error", "Text exceeds 4096 characters"))
                            return
                        }
                        accepted = RemoteAccessibilityService.setFocusedText(value)
                        action = "text"
                    }
                }
                if (!accepted) {
                    respond(output, 503, JSONObject().put("error", "Accessibility service unavailable, no focused editable field, or gesture rejected"))
                } else {
                    respond(output, 202, JSONObject().put("accepted", true).put("action", action))
                }
                return
            }
            if (parts[0] != "GET") {
                respond(output, 404, JSONObject().put("error", "Endpoint not found"))
                return
            }
            if (endpoint == "/screen.jpg") {
                val frame = ScreenFrameStore.latestJpeg
                if (frame == null) {
                    respond(output, 503, JSONObject().put("error", "Screen sharing is not active or no frame is ready"))
                } else {
                    respondBytes(output, frame)
                }
                return
            }
            val response = when (endpoint) {
                "/health" -> JSONObject()
                    .put("app", "RKRemote")
                    .put("status", "online")
                    .put("mode", "owner-authorized-lan")
                "/files" -> listSelectedFolder()
                else -> {
                    respond(output, 404, JSONObject().put("error", "Not found"))
                    return
                }
            }
            respond(output, 200, response)
        }
    }

    /** Reads one bounded ASCII HTTP line without allowing an oversized line allocation. */
    private fun readAsciiLine(input: InputStream, maxBytes: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                break
            }
            if (value == 10) break
            if (value != 13) {
                if (bytes.size() >= maxBytes) throw java.io.IOException("HTTP line too large")
                bytes.write(value)
            }
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun readExact(input: InputStream, length: Int): ByteArray? {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) return null
            offset += count
        }
        return result
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
            202 -> "Accepted"
            400 -> "Bad Request"
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
        private const val MAX_REQUEST_LINE = 2048
        private const val MAX_HEADER_LINE_BYTES = 2048
        private const val MAX_HEADERS = 24
        private const val MAX_HEADER_BYTES = 8192
        private const val MAX_INPUT_BODY_BYTES = 16 * 1024\n        private const val MAX_REMOTE_TEXT_LENGTH = 4096
    }
}
