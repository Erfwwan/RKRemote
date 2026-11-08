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
    fun request(ip: String, token: String, endpoint: String): String {
        require(ip.matches(Regex("""(?:\d{1,3}\.){3}\d{1,3}"""))) { "Enter the target phone's IPv4 address" }
        require(token.length >= 24) { "Invalid session token" }
        require(endpoint == "/health" || endpoint == "/files")
        val socket = Socket()
        socket.connect(java.net.InetSocketAddress(ip, LanAgentServer.DEFAULT_PORT), 5000)
        socket.soTimeout = 7000
        socket.use { s ->
            val request = "GET $endpoint HTTP/1.1\r\n" +
                "Host: $ip\r\n" +
                "Authorization: Bearer $token\r\n" +
                "Connection: close\r\n\r\n"
            s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val response = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val split = response.indexOf("\r\n\r\n")
            if (split < 0) throw IllegalStateException("Invalid response from target")
            val status = response.substringBefore("\r\n")
            val body = response.substring(split + 4)
            if (!status.contains(" 200 ")) throw IllegalStateException("Target response: $status - $body")
            return JSONObject(body).toString(2)
        }
    }

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
