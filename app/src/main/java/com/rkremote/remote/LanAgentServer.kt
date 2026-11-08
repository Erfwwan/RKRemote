package com.rkremote.remote

import android.content.Context
import android.content.ComponentName
import android.provider.Settings
import com.rkremote.EncryptedNotificationStore
import com.rkremote.NotificationCaptureService
import android.net.Uri
import android.provider.DocumentsContract
import java.net.URLDecoder
import java.util.Base64
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.DataOutputStream
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
    private val port: Int = DEFAULT_PORT,
    private val onFatalError: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    private var worker: Thread? = null
    @Volatile private var lastError: String? = null
    private val startedAtMillis = System.currentTimeMillis()
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
                ServerSocket().use { socketServer ->
                    socketServer.reuseAddress = true
                    socketServer.bind(java.net.InetSocketAddress(port), 8)
                    server = socketServer
                    lastError = null
                    while (running.get()) {
                        try {
                            val client = socketServer.accept().apply {
                                soTimeout = 30_000
                                tcpNoDelay = true
                                keepAlive = true
                            }
                            clientSockets.add(client)
                            val task = ClientTask(client)
                            try {
                                clients.execute(task)
                            } catch (_: java.util.concurrent.RejectedExecutionException) {
                                task.close()
                            }
                        } catch (e: SocketException) {
                            if (running.get()) throw e
                        }
                    }
                }
            } catch (error: Exception) {
                if (running.get()) {
                    val reason = error.message ?: error.javaClass.simpleName
                    lastError = reason
                    runCatching { onFatalError(reason) }
                }
                running.set(false)
            } finally {
                server = null
                if (!running.get()) {
                    clientSockets.toList().forEach { runCatching { it.close() } }
                    clientSockets.clear()
                    clients.shutdownNow().forEach { task ->
                        if (task is ClientTask) task.close()
                    }
                }
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
            client.soTimeout = 30_000
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
            if (parts[0] == "POST" && endpoint == "/audio/ptt") {
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 1..PttAudioQueue.MAX_CHUNK_BYTES) {
                    respond(output, 400, JSONObject().put("error", "PTT chunk must be 1..4096 bytes"))
                    return
                }
                if (headers["content-type"]?.substringBefore(';') != "application/octet-stream") {
                    respond(output, 415, JSONObject().put("error", "PTT audio must be application/octet-stream PCM"))
                    return
                }
                val chunk = readExact(input, length)
                    ?: run {
                        respond(output, 400, JSONObject().put("error", "Incomplete PTT audio chunk"))
                        return
                    }
                if (!PttAudioQueue.offer(chunk)) {
                    respond(output, 503, JSONObject().put("error", "PTT speaker is not enabled locally on the target"))
                } else {
                    respond(output, 202, JSONObject().put("accepted", true))
                }
                return
            }
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
            if (parts[0] == "POST" && endpoint == "/file/upload") {
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 1..MAX_TRANSFER_BYTES) {
                    respond(output, 400, JSONObject().put("error", "Upload must be 1 byte to 8 MiB"))
                    return
                }
                val encodedName = headers["x-file-name"] ?: ""
                val fileName = runCatching {
                    String(Base64.getUrlDecoder().decode(encodedName), Charsets.UTF_8)
                }.getOrNull()?.takeIf {
                    it.isNotBlank() && it.length <= 180 && it != "." && it != ".." &&
                        it.none { ch -> ch == '/' || ch == '\\' || ch.code < 32 }
                }
                if (fileName == null) {
                    respond(output, 400, JSONObject().put("error", "Invalid file name"))
                    return
                }
                val mime = headers["content-type"]?.substringBefore(';')
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) }
                    ?: "application/octet-stream"
                val bytes = readExact(input, length)
                    ?: run {
                        respond(output, 400, JSONObject().put("error", "Incomplete upload"))
                        return
                    }
                val result = runCatching { saveUploadedFile(fileName, mime, bytes) }
                if (result.isFailure) {
                    respond(output, 400, JSONObject().put("error", result.exceptionOrNull()?.message ?: "Upload failed"))
                } else {
                    respond(output, 201, JSONObject().put("uploaded", true)
                        .put("name", fileName).put("size", bytes.size))
                }
                return
            }
            if (parts[0] == "GET" && endpoint == "/notifications") {
                val sharingEnabled = context.getSharedPreferences("rkremote_setup", Context.MODE_PRIVATE)
                    .getBoolean("notification_sharing_enabled", false)
                if (!sharingEnabled || !hasNotificationListenerAccess()) {
                    respond(output, 403, JSONObject().put(
                        "error",
                        "Notification sharing is disabled locally or Notification Access is unavailable"
                    ))
                    return
                }
                val stored = EncryptedNotificationStore.readAll(context)
                val start = (stored.length() - MAX_NOTIFICATION_EVENTS).coerceAtLeast(0)
                val exported = JSONArray()
                for (index in start until stored.length()) {
                    val event = stored.optJSONObject(index) ?: continue
                    exported.put(JSONObject()
                        .put("type", event.optString("type", "posted"))
                        .put("package", event.optString("package", ""))
                        .put("title", event.optString("title", "").take(MAX_NOTIFICATION_FIELD_CHARS))
                        .put("text", event.optString("text", "").take(MAX_NOTIFICATION_FIELD_CHARS))
                        .put("subText", event.optString("subText", "").take(MAX_NOTIFICATION_FIELD_CHARS))
                        .put("conversation", event.optString("conversation", "").take(MAX_NOTIFICATION_FIELD_CHARS))
                        .put("postedAt", event.optLong("postedAt", 0L))
                        .put("capturedAt", event.optLong("capturedAt", 0L))
                        .put("notificationId", event.optInt("notificationId", 0)))
                }
                respond(output, 200, JSONObject()
                    .put("items", exported)
                    .put("count", exported.length())
                    .put("truncated", start > 0))
                return
            }
            if (parts[0] != "GET") {
                respond(output, 404, JSONObject().put("error", "Endpoint not found"))
                return
            }
            if (endpoint == "/file/download") {
                val documentId = queryParameter(parts[1], "id")
                if (documentId.isNullOrBlank()) {
                    respond(output, 400, JSONObject().put("error", "Missing document id"))
                    return
                }
                val downloaded = runCatching { readSelectedFile(documentId) }
                if (downloaded.isFailure) {
                    respond(output, 404, JSONObject().put("error", downloaded.exceptionOrNull()?.message ?: "File unavailable"))
                } else {
                    val (name, mime, bytes) = downloaded.getOrThrow()
                    respondFileBytes(output, bytes, name, mime)
                }
                return
            }
            if (endpoint == "/screen/video") {
                streamScreenVideo(client, output)
                return
            }
            if (endpoint == "/camera.jpg") {
                val frame = CameraFrameStore.latestJpeg
                if (frame == null) {
                    respond(output, 503, JSONObject().put("error", "Camera is not active or no frame is ready"))
                } else {
                    respondBytes(output, frame)
                }
                return
            }
            if (endpoint == "/audio/chunk") {
                val chunk = MicrophoneChunkStore.take()
                if (chunk == null) respondNoContent(output) else respondAudioBytes(output, chunk)
                return
            }
            val response = when (endpoint) {
                "/health" -> JSONObject()
                    .put("app", "RKRemote")
                    .put("status", if (running.get()) "online" else "offline")
                    .put("mode", "owner-authorized-lan")
                    .put("uptimeMs", (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L))
                    .put("activeClients", clientSockets.size)
                    .put("queuedClients", clients.queue.size)
                    .put("lastError", lastError ?: JSONObject.NULL)
                "/files" -> listSelectedFolder()
                else -> {
                    respond(output, 404, JSONObject().put("error", "Not found"))
                    return
                }
            }
            respond(output, 200, response)
        }
    }

    /**
     * Persistent length-framed H.264 stream. A slow socket receives the newest
     * access unit on the next iteration; stale intermediate frames are skipped.
     */
    private fun streamScreenVideo(socket: Socket, output: OutputStream) {
        val config = ScreenVideoStore.awaitConfig(5_000)
        if (config == null) {
            respond(output, 503, JSONObject().put("error", "Owner-approved screen video is not active"))
            return
        }
        val stream = DataOutputStream(output)
        stream.write(
            ("HTTP/1.1 200 OK\\r\\n" +
                "Content-Type: application/vnd.rkremote.h264-stream\\r\\n" +
                "Connection: close\\r\\nCache-Control: no-store\\r\\n\\r\\n")
                .toByteArray(Charsets.US_ASCII)
        )
        stream.write("RKV1".toByteArray(Charsets.US_ASCII))
        stream.writeInt(config.width)
        stream.writeInt(config.height)
        stream.writeInt(config.csd0.size)
        stream.write(config.csd0)
        stream.writeInt(config.csd1.size)
        stream.write(config.csd1)
        stream.flush()
        var lastSequence = 0L
        while (running.get() && !socket.isClosed) {
            val frame = ScreenVideoStore.awaitNext(lastSequence, 2_000) ?: run { if (!ScreenVideoStore.hasConfig()) break else continue }
            if (frame.bytes.size > ScreenVideoStore.MAX_ACCESS_UNIT) break
            stream.writeLong(frame.ptsUs)
            stream.writeInt(frame.flags)
            stream.writeInt(frame.bytes.size)
            stream.write(frame.bytes)
            stream.flush()
            lastSequence = frame.sequence
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

    private fun hasNotificationListenerAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = ComponentName(context, NotificationCaptureService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun selectedTree(): DocumentFile {
        val raw = context.getSharedPreferences("rkremote_setup", Context.MODE_PRIVATE)
            .getString("selected_tree_uri", null) ?: throw SecurityException("No owner-selected folder")
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(raw))
            ?: throw java.io.IOException("Selected folder is unavailable")
        if (!tree.canRead()) throw SecurityException("Selected folder is not readable")
        return tree
    }

    private fun queryParameter(target: String, name: String): String? =
        target.substringAfter('?', "").split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=', "")
            if (runCatching { URLDecoder.decode(key, "UTF-8") }.getOrNull() == name)
                runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull()
            else null
        }

    private fun readSelectedFile(documentId: String): Triple<String, String, ByteArray> {
        val tree = selectedTree()
        val child = tree.listFiles().firstOrNull { file ->
            runCatching { DocumentsContract.getDocumentId(file.uri) == documentId }.getOrDefault(false)
        } ?: throw SecurityException("File is not a direct child of the selected folder")
        if (!child.isFile || !child.canRead()) throw SecurityException("Selected item is not a readable file")
        if (child.length() > MAX_TRANSFER_BYTES) throw java.io.IOException("File exceeds 8 MiB transfer limit")
        val bytes = context.contentResolver.openInputStream(child.uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_TRANSFER_BYTES) throw java.io.IOException("File exceeds 8 MiB transfer limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw java.io.IOException("Unable to read selected file")
        return Triple(child.name ?: "download.bin", child.type ?: "application/octet-stream", bytes)
    }

    private fun saveUploadedFile(name: String, mime: String, bytes: ByteArray) {
        val tree = selectedTree()
        if (!tree.canWrite()) throw SecurityException("Selected folder is not writable")
        if (tree.findFile(name) != null) throw java.io.IOException("A file with this name already exists")
        val created = tree.createFile(mime, name) ?: throw java.io.IOException("Unable to create file in selected folder")
        try {
            context.contentResolver.openOutputStream(created.uri, "w")?.use { it.write(bytes) }
                ?: throw java.io.IOException("Unable to open destination file")
        } catch (error: Exception) {
            runCatching { created.delete() }
            throw error
        }
    }

    private fun respondFileBytes(output: OutputStream, bytes: ByteArray, name: String, mime: String) {
        val safeName = Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "X-File-Name: $safeName\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
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
                .put("id", runCatching { DocumentsContract.getDocumentId(file.uri) }.getOrDefault(""))
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

    private fun respondAudioBytes(output: OutputStream, bytes: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun respondNoContent(output: OutputStream) {
        val header = "HTTP/1.1 204 No Content\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun respond(output: OutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val label = when (status) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
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
        private const val MAX_INPUT_BODY_BYTES = 16 * 1024
        private const val MAX_REMOTE_TEXT_LENGTH = 4096
        private const val MAX_TRANSFER_BYTES = 8 * 1024 * 1024
        private const val MAX_NOTIFICATION_EVENTS = 100
        private const val MAX_NOTIFICATION_FIELD_CHARS = 2000
    }
}
