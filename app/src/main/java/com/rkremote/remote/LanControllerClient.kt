package com.rkremote.remote

import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLEncoder
import java.util.Base64

/**
 * LAN client for a trusted private network. The temporary bearer token is not
 * encrypted in transit; never use this transport on an untrusted network.
 */
object LanControllerClient {
    fun request(ip: String, token: String, endpoint: String): String =
        JSONObject(requestBytes(ip, token, endpoint).toString(Charsets.UTF_8)).toString(2)

    fun requestBytes(ip: String, token: String, endpoint: String): ByteArray =
        requestRaw("GET", ip, token, endpoint, null)

    fun downloadFile(ip: String, token: String, documentId: String): ByteArray {
        require(documentId.isNotBlank() && documentId.length <= 1024)
        val encoded = URLEncoder.encode(documentId, "UTF-8")
        return requestBytes(ip, token, "/file/download?id=$encoded")
    }

    fun postJson(ip: String, token: String, endpoint: String, body: JSONObject): String =
        JSONObject(requestRaw("POST", ip, token, endpoint, body.toString().toByteArray(Charsets.UTF_8))
            .toString(Charsets.UTF_8)).toString(2)

    fun uploadFile(ip: String, token: String, fileName: String, mimeType: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_TRANSFER_BYTES) { "File must be 1 byte to 8 MiB" }
        require(fileName.isNotBlank() && fileName.length <= 180 &&
            fileName.none { it == '/' || it == '\\' || it.code < 32 }) { "Invalid file name" }
        val encodedName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(fileName.toByteArray(Charsets.UTF_8))
        val mime = mimeType.takeIf {
            it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
        } ?: "application/octet-stream"
        return requestRaw(
            "POST", ip, token, "/file/upload", bytes, mime,
            mapOf("X-File-Name" to encodedName)
        ).toString(Charsets.UTF_8)
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
        require(ip.matches(Regex("""(?:\d{1,3}\.){3}\d{1,3}""")) &&
            ip.split(".").all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            "Enter a valid target IPv4 address"
        }
        require(token.length >= 24) { "Invalid session token" }
        require(
            (method == "GET" && (endpoint in setOf("/health", "/files", "/screen.jpg", "/camera.jpg", "/audio/chunk") ||
                endpoint.startsWith("/file/download?"))) ||
                (method == "POST" && endpoint in setOf("/input/tap", "/input/gesture", "/input/text", "/file/upload"))
        ) { "Unsupported request" }

        val socket = Socket()
        socket.connect(InetSocketAddress(ip, LanAgentServer.DEFAULT_PORT), 5000)
        socket.soTimeout = if (endpoint.startsWith("/file/")) 30_000 else 7000
        socket.use { s ->
            val headers = StringBuilder()
                .append("$method $endpoint HTTP/1.1\r\n")
                .append("Host: $ip\r\n")
                .append("Authorization: Bearer $token\r\n")
                .append("Connection: close\r\n")
            if (body != null) {
                headers.append("Content-Type: $contentType\r\n")
                    .append("Content-Length: ${body.size}\r\n")
                extraHeaders.forEach { (name, value) -> headers.append("$name: $value\r\n") }
            }
            headers.append("\r\n")
            val outputStream = s.getOutputStream()
            outputStream.write(headers.toString().toByteArray(Charsets.US_ASCII))
            if (body != null) outputStream.write(body)
            outputStream.flush()

            val response = s.getInputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_RESPONSE_BYTES) { "Response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val split = (0 until (response.size - 3).coerceAtLeast(0)).firstOrNull { i ->
                response[i] == 13.toByte() && response[i + 1] == 10.toByte() &&
                    response[i + 2] == 13.toByte() && response[i + 3] == 10.toByte()
            } ?: throw IllegalStateException("Invalid response from target")
            val status = response.copyOfRange(0, split).toString(Charsets.US_ASCII).substringBefore("\r\n")
            if (!status.contains(" 200 ") && !status.contains(" 201 ") && !status.contains(" 202 ") && !status.contains(" 204 ")) {
                val responseBody = response.copyOfRange(split + 4, response.size).toString(Charsets.UTF_8)
                throw IllegalStateException("Target response: $status - $responseBody")
            }
            return response.copyOfRange(split + 4, response.size)
        }
    }

    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    private const val MAX_TRANSFER_BYTES = 8 * 1024 * 1024

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
