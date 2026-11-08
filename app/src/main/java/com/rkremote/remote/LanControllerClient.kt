package com.rkremote.remote

import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * LAN client for a trusted private network. The temporary bearer token is not
 * encrypted in transit; never use this transport on an untrusted network.
 */
object LanControllerClient {
    fun request(ip: String, token: String, endpoint: String): String =
        JSONObject(requestBytes(ip, token, endpoint).toString(Charsets.UTF_8)).toString(2)

    fun requestBytes(ip: String, token: String, endpoint: String): ByteArray =
        requestRaw("GET", ip, token, endpoint, null)

    fun postJson(ip: String, token: String, endpoint: String, body: JSONObject): String =
        JSONObject(requestRaw("POST", ip, token, endpoint, body.toString().toByteArray(Charsets.UTF_8))
            .toString(Charsets.UTF_8)).toString(2)

    private fun requestRaw(
        method: String,
        ip: String,
        token: String,
        endpoint: String,
        body: ByteArray?
    ): ByteArray {
        require(ip.matches(Regex("""(?:\d{1,3}\.){3}\d{1,3}""")) &&
            ip.split(".").all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            "Enter a valid target IPv4 address"
        }
        require(token.length >= 24) { "Invalid session token" }
        require(
            (method == "GET" && endpoint in setOf("/health", "/files", "/screen.jpg")) ||
                (method == "POST" && endpoint in setOf("/input/tap", "/input/gesture", "/input/text"))
        ) { "Unsupported request" }

        val socket = Socket()
        socket.connect(InetSocketAddress(ip, LanAgentServer.DEFAULT_PORT), 5000)
        socket.soTimeout = 7000
        socket.use { s ->
            val headers = StringBuilder()
                .append("$method $endpoint HTTP/1.1\r\n")
                .append("Host: $ip\r\n")
                .append("Authorization: Bearer $token\r\n")
                .append("Connection: close\r\n")
            if (body != null) {
                headers.append("Content-Type: application/json\r\n")
                    .append("Content-Length: ${body.size}\r\n")
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
            if (!status.contains(" 200 ") && !status.contains(" 202 ")) {
                val responseBody = response.copyOfRange(split + 4, response.size).toString(Charsets.UTF_8)
                throw IllegalStateException("Target response: $status - $responseBody")
            }
            return response.copyOfRange(split + 4, response.size)
        }
    }

    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
