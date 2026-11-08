package com.rkremote.remote

import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

/**
 * Minimal read-only client for a trusted local Wi-Fi network.
 * Uses a raw TCP socket so Android cleartext HTTP policy is not weakened globally.
 */
object LanControllerClient {
    fun request(ip: String, token: String, endpoint: String): String =
        JSONObject(requestBytes(ip, token, endpoint).toString(Charsets.UTF_8)).toString(2)

    fun requestBytes(ip: String, token: String, endpoint: String): ByteArray {
        require(ip.matches(Regex("""(?:\d{1,3}\.){3}\d{1,3}""")) &&
            ip.split(".").all { it.toIntOrNull() in 0..255 }) { "Enter a valid target IPv4 address" }
        require(token.length >= 24) { "Invalid session token" }
        require(endpoint == "/health" || endpoint == "/files" || endpoint == "/screen.jpg")
        val socket = Socket()
        socket.connect(java.net.InetSocketAddress(ip, LanAgentServer.DEFAULT_PORT), 5000)
        socket.soTimeout = 7000
        socket.use { s ->
            val request = "GET $endpoint HTTP/1.1\r\n" +
                "Host: $ip\r\n" +
                "Authorization: Bearer $token\r\n" +
                "Connection: close\r\n\r\n"
            s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
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
            if (!status.contains(" 200 ")) {
                val body = response.copyOfRange(split + 4, response.size).toString(Charsets.UTF_8)
                throw IllegalStateException("Target response: $status - $body")
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
