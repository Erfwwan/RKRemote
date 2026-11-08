package com.rkremote.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Public, short-lived DHT advertisement. It contains no session token,
 * private key, notification data, or media. The signature binds the peer ID,
 * public key, sequence, validity window, and advertised multiaddresses.
 */
data class P2pPeerRecord(
    val peerId: String,
    val publicKeyBase64: String,
    val sequence: Long,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val addresses: List<String>,
    val signatureBase64: String
) {
    fun verify(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (sequence < 0L) return false
        if (issuedAtMillis > nowMillis + CLOCK_SKEW_MS) return false
        if (expiresAtMillis <= nowMillis) return false
        if (expiresAtMillis <= issuedAtMillis ||
            expiresAtMillis - issuedAtMillis > MAX_TTL_MS) return false
        if (addresses.size > MAX_ADDRESSES || addresses.any { !isValidAddress(it) }) return false

        val publicBytes = runCatching { Base64.getDecoder().decode(publicKeyBase64) }.getOrNull()
            ?: return false
        val calculatedPeerId = peerIdFor(publicBytes)
        if (calculatedPeerId != peerId) return false

        val publicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
        }.getOrNull() ?: return false
        val signatureBytes = runCatching {
            Base64.getDecoder().decode(signatureBase64)
        }.getOrNull() ?: return false

        return runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(canonicalBytes())
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("recordVersion", RECORD_VERSION)
        .put("peerId", peerId)
        .put("publicKey", publicKeyBase64)
        .put("sequence", sequence)
        .put("issuedAt", issuedAtMillis)
        .put("expiresAt", expiresAtMillis)
        .put("addresses", JSONArray(addresses))
        .put("signature", signatureBase64)

    internal fun signingBytes(): ByteArray = canonicalBytes()

    private fun canonicalBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(RECORD_VERSION)
            output.writeUTF(peerId)
            output.writeUTF(publicKeyBase64)
            output.writeLong(sequence)
            output.writeLong(issuedAtMillis)
            output.writeLong(expiresAtMillis)
            output.writeInt(addresses.size)
            addresses.forEach(output::writeUTF)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val RECORD_VERSION = 1
        const val MAX_TTL_MS = 10 * 60 * 1000L
        const val MAX_ADDRESSES = 8
        const val MAX_RECORD_BYTES = 8 * 1024
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val CLOCK_SKEW_MS = 60 * 1000L

        fun create(
            keyPair: KeyPair,
            sequence: Long,
            addresses: List<String>,
            nowMillis: Long = System.currentTimeMillis(),
            ttlMillis: Long = MAX_TTL_MS
        ): P2pPeerRecord {
            require(sequence >= 0L) { "Sequence must not be negative" }
            require(ttlMillis in 1..MAX_TTL_MS) { "Invalid peer-record lifetime" }
            require(addresses.size <= MAX_ADDRESSES && addresses.all(::isValidAddress)) {
                "Invalid advertised multiaddress list"
            }
            val publicBytes = keyPair.public.encoded
            val publicKeyBase64 = Base64.getEncoder().withoutPadding().encodeToString(publicBytes)
            val unsigned = P2pPeerRecord(
                peerId = peerIdFor(publicBytes),
                publicKeyBase64 = publicKeyBase64,
                sequence = sequence,
                issuedAtMillis = nowMillis,
                expiresAtMillis = nowMillis + ttlMillis,
                addresses = addresses.distinct().sorted(),
                signatureBase64 = ""
            )
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(keyPair.private)
                update(unsigned.canonicalBytes())
                sign()
            }
            return unsigned.copy(
                signatureBase64 = Base64.getEncoder().withoutPadding().encodeToString(signature)
            ).also {
                require(it.toJson().toString().toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) {
                    "Peer record exceeds maximum size"
                }
            }
        }

        fun parse(
            raw: String,
            nowMillis: Long = System.currentTimeMillis(),
            expectedPeerId: String? = null
        ): P2pPeerRecord {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) {
                "Peer record exceeds maximum size"
            }
            val json = JSONObject(raw)
            require(json.getInt("recordVersion") == RECORD_VERSION) {
                "Unsupported peer-record version"
            }
            val array = json.getJSONArray("addresses")
            require(array.length() <= MAX_ADDRESSES) { "Too many peer addresses" }
            val record = P2pPeerRecord(
                peerId = json.getString("peerId"),
                publicKeyBase64 = json.getString("publicKey"),
                sequence = json.getLong("sequence"),
                issuedAtMillis = json.getLong("issuedAt"),
                expiresAtMillis = json.getLong("expiresAt"),
                addresses = (0 until array.length()).map { array.getString(it) },
                signatureBase64 = json.getString("signature")
            )
            require(record.verify(nowMillis)) { "Peer record signature or validity check failed" }
            if (expectedPeerId != null) {
                require(record.peerId == expectedPeerId) { "Peer record does not match the expected paired device" }
            }
            return record
        }

        fun peerIdFor(publicKeyBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun isValidAddress(value: String): Boolean {
            if (value.length !in 1..256 || !value.startsWith("/") ||
                value.any { it.isWhitespace() || it.code < 0x21 }) return false
            // Only transport addresses are publishable; reject URLs and arbitrary payload strings.
            return (value.startsWith("/ip4/") || value.startsWith("/ip6/") ||
                value.startsWith("/dns4/") || value.startsWith("/dns6/")) &&
                (value.contains("/tcp/") || value.contains("/udp/"))
        }
    }
}
