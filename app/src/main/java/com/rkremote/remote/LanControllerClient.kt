package com.rkremote.remote

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLEncoder
import java.util.Base64

/** LAN client. The current temporary-token transport is plaintext; use trusted private Wi-Fi only. */
object LanControllerClient {
    fun request(ip: String, token: String, endpoint: String): String =
        JSONObject(requestBytes(ip, token, endpoint).toString(Charsets.UTF_8)).toString(2)

    fun requestBytes(ip: String, token: String, endpoint: String): ByteArray =
        requestRaw("GET", ip, token, endpoint, null)

    fun requestScreenshot(ip: String, token: String): ByteArray =
        requestRaw("GET", ip, token, "/screenshot.jpg", null)

    fun saveScreenshotToDownloads(context: Context, jpeg: ByteArray): String {
        require(jpeg.isNotEmpty()) { "Screenshot is empty" }
        val fileName = "RKRemote-${System.currentTimeMillis()}.jpg"
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/RKRemote/Screenshots/"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create screenshot in Download/RKRemote/Screenshots")
            try {
                resolver.openOutputStream(uri, "w")?.use { it.write(jpeg) }
                    ?: throw IllegalStateException("Unable to open screenshot output")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                return relativePath + fileName
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "RKRemote/Screenshots"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create screenshot folder")
        }
        val destination = File(directory, fileName)
        FileOutputStream(destination).use { it.write(jpeg) }
        return destination.absolutePath
    }

    fun downloadFileToReceivedFolder(
        context: Context,
        ip: String,
        token: String,
        documentId: String,
        originalName: String,
        mimeType: String
    ): String {
        val safeName = safeDisplayName(originalName)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/RKRemote/Received/"
            val finalName = uniqueMediaStoreName(context, relativePath, safeName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create file in Download/RKRemote/Received")
            try {
                resolver.openOutputStream(uri, "w")?.use { destination ->
                    downloadStream(ip, token, documentId, destination)
                } ?: throw IllegalStateException("Unable to open received file")
                val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, ready, null, null)
                return relativePath + finalName
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }

        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "RKRemote/Received"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create RKRemote/Received folder")
        }
        val destination = uniqueFile(directory, safeName)
        try {
            FileOutputStream(destination).use { downloadStream(ip, token, documentId, it) }
            return destination.absolutePath
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun uploadFile(
        context: Context,
        ip: String,
        token: String,
        sourceUri: Uri,
        fileName: String,
        mimeType: String,
        targetDirectoryId: String
    ): String {
        val resolver = context.contentResolver
        val lengthFromQuery = resolver.query(
            sourceUri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
        val descriptor = resolver.openAssetFileDescriptor(sourceUri, "r")
            ?: throw IllegalStateException("Unable to open selected file")
        descriptor.use { afd ->
            val length = if (lengthFromQuery >= 0L) lengthFromQuery else afd.length
            require(length >= 0L) {
                "This document provider does not report file size, so a safe streaming upload cannot be started"
            }
            val input = resolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("Unable to read selected file")
            input.use {
                val encodedName = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(safeDisplayName(fileName).toByteArray(Charsets.UTF_8))
                val encodedDirectory = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(targetDirectoryId.toByteArray(Charsets.UTF_8))
                val mime = mimeType.takeIf {
                    it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
                } ?: "application/octet-stream"
                val response = streamingRequest(
                    "POST", ip, token, "/file/upload", it, length, mime,
                    mapOf("X-File-Name" to encodedName, "X-Target-Directory" to encodedDirectory)
                )
                return JSONObject(response.toString(Charsets.UTF_8)).toString(2)
            }
        }
    }

    fun postJson(ip: String, token: String, endpoint: String, body: JSONObject): String =
        JSONObject(requestRaw("POST", ip, token, endpoint, body.toString().toByteArray(Charsets.UTF_8))
            .toString(Charsets.UTF_8)).toString(2)

    fun postPttChunk(ip: String, token: String, bytes: ByteArray): Boolean {
        require(bytes.isNotEmpty() && bytes.size <= PttAudioQueue.MAX_CHUNK_BYTES) {
            "PTT audio chunk must be 1..4096 bytes"
        }
        val response = requestRaw(
            "POST", ip, token, "/audio/ptt", bytes, "application/octet-stream"
        )
        return JSONObject(response.toString(Charsets.UTF_8)).optBoolean("accepted", false)
    }

    private fun downloadStream(ip: String, token: String, documentId: String, destination: java.io.OutputStream) {
        val encoded = URLEncoder.encode(documentId, "UTF-8")
        val response = openRequest("GET", ip, token, "/file/download?id=$encoded", null, null, emptyMap())
        response.use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val headers = readResponseHeaders(input)
            check(headers.first.contains(" 200 ")) {
                "Target response: ${headers.first} - ${readBodyText(input)}"
            }
            val length = headers.second["content-length"]?.toLongOrNull()
            val buffer = ByteArray(64 * 1024)
            if (length != null) {
                var remaining = length
                while (remaining > 0L) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IllegalStateException("Download interrupted before completion")
                    destination.write(buffer, 0, count)
                    remaining -= count.toLong()
                }
            } else {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                }
            }
            destination.flush()
        }
    }

    private fun streamingRequest(
        method: String,
        ip: String,
        token: String,
        endpoint: String,
        body: InputStream,
        length: Long,
        contentType: String,
        extraHeaders: Map<String, String>
    ): ByteArray {
        val socket = openRequest(method, ip, token, endpoint, length, contentType, extraHeaders)
        socket.use { s ->
            val output = s.getOutputStream()
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0L) {
                val count = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IllegalStateException("Selected file ended before its reported size")
                output.write(buffer, 0, count)
                remaining -= count.toLong()
            }
            output.flush()
            val input = BufferedInputStream(s.getInputStream())
            val headers = readResponseHeaders(input)
            val bodyBytes = readResponseBody(input, headers.second["content-length"]?.toLongOrNull(), 1024 * 1024)
            check(headers.first.contains(" 200 ") || headers.first.contains(" 201 ")) {
                "Target response: ${headers.first} - ${bodyBytes.toString(Charsets.UTF_8)}"
            }
            return bodyBytes
        }
    }

    private fun openRequest(
        method: String,
        ip: String,
        token: String,
        endpoint: String,
        contentLength: Long?,
        contentType: String?,
        extraHeaders: Map<String, String>
    ): Socket {
        require(ip.matches(Regex("""(?:\d{1,3}\.){3}\d{1,3}""")) &&
            ip.split(".").all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            "Enter a valid target IPv4 address"
        }
        require(token.length >= 24) { "Invalid session token" }
        val socket = Socket()
        socket.connect(InetSocketAddress(ip, LanAgentServer.DEFAULT_PORT), 10_000)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = if (endpoint.startsWith("/file/")) 120_000 else 30_000
        val headers = StringBuilder()
            .append("$method $endpoint HTTP/1.1\r\n")
            .append("Host: $ip\r\n")
            .append("Authorization: Bearer $token\r\n")
            .append("Connection: close\r\n")
        if (contentLength != null) {
            headers.append("Content-Type: ${contentType ?: "application/octet-stream"}\r\n")
                .append("Content-Length: $contentLength\r\n")
        }
        extraHeaders.forEach { (name, value) -> headers.append("$name: $value\r\n") }
        headers.append("\r\n")
        socket.getOutputStream().write(headers.toString().toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        return socket
    }

    private fun requestRaw(
        method: String,
        ip: String,
        token: String,
        endpoint: String,
        body: ByteArray?,
        contentType: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap()
    ): ByteArray {
        require(
            (method == "GET" && (endpoint in setOf("/health", "/files", "/camera.jpg", "/screenshot.jpg", "/audio/chunk", "/notifications") ||
                endpoint.startsWith("/files?") || endpoint.startsWith("/file/download?"))) ||
                (method == "POST" && endpoint in setOf("/input/tap", "/input/gesture", "/input/text", "/audio/ptt"))
        ) { "Unsupported request" }
        val socket = openRequest(method, ip, token, endpoint, body?.size?.toLong(), contentType, extraHeaders)
        socket.use { s ->
            if (body != null) {
                s.getOutputStream().write(body)
                s.getOutputStream().flush()
            }
            val input = BufferedInputStream(s.getInputStream())
            val headers = readResponseHeaders(input)
            val bytes = readResponseBody(input, headers.second["content-length"]?.toLongOrNull(), MAX_RESPONSE_BYTES.toLong())
            check(headers.first.contains(" 200 ") || headers.first.contains(" 201 ") ||
                headers.first.contains(" 202 ") || headers.first.contains(" 204 ")) {
                "Target response: ${headers.first} - ${bytes.toString(Charsets.UTF_8)}"
            }
            return bytes
        }
    }

    private fun readResponseHeaders(input: InputStream): Pair<String, Map<String, String>> {
        val status = readAsciiLine(input) ?: throw IllegalStateException("Empty response from target")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: throw IllegalStateException("Incomplete response headers")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        return status to headers
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
            if (value == 10) break
            if (value != 13) {
                if (bytes.size() >= 8192) throw IllegalStateException("Response header line too long")
                bytes.write(value)
            }
        }
        return bytes.toString(Charsets.US_ASCII.name())
    }

    private fun readResponseBody(input: InputStream, length: Long?, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        if (length != null) {
            require(length in 0..maxBytes) { "Response is too large" }
            var remaining = length
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IllegalStateException("Incomplete response body")
                output.write(buffer, 0, count)
                remaining -= count.toLong()
            }
        } else {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "Response is too large" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun readBodyText(input: InputStream): String =
        runCatching { readResponseBody(input, null, 1024 * 1024).toString(Charsets.UTF_8) }.getOrDefault("")

    private fun safeDisplayName(value: String): String {
        val clean = value.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 32 && it != '/' && it != '\\' }
            .take(255)
            .trim()
        return clean.ifBlank { "received-file.bin" }
    }

    private fun uniqueMediaStoreName(context: Context, relativePath: String, original: String): String {
        val resolver = context.contentResolver
        val base = original.substringBeforeLast('.', original)
        val ext = original.substringAfterLast('.', "").let { if (it.isNotEmpty() && original.contains('.')) ".$it" else "" }
        var candidate = original
        var index = 1
        while (resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(relativePath, candidate),
                null
            )?.use { it.moveToFirst() } == true
        ) {
            candidate = "$base ($index)$ext"
            index++
        }
        return candidate
    }

    private fun uniqueFile(directory: File, original: String): File {
        var candidate = File(directory, original)
        val base = original.substringBeforeLast('.', original)
        val ext = original.substringAfterLast('.', "").let { if (it.isNotEmpty() && original.contains('.')) ".$it" else "" }
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    private const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
