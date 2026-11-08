package com.rkremote.remote

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val version: Int,
    val algorithm: String,
    val nonceBase64: String,
    val ciphertextBase64: String
)

/**
 * Authenticated encryption for application payloads after a separately
 * authenticated pairing handshake has produced a random 256-bit session key.
 *
 * This class does not establish trust or derive keys from a short pairing code.
 * Callers must persist neither plaintext secrets nor session keys.
 */
class SecurePayload(
    sessionKey: ByteArray,
    private val random: SecureRandom = SecureRandom()
) {
    private val key = sessionKey.copyOf()

    init {
        require(key.size == KEY_BYTES) { "Session key must be 256 bits" }
    }

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = byteArrayOf()): EncryptedPayload {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Payload too large" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(
            version = VERSION,
            algorithm = "A256GCM",
            nonceBase64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(nonce),
            ciphertextBase64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(ciphertext)
        )
    }

    fun decrypt(envelope: EncryptedPayload, associatedData: ByteArray = byteArrayOf()): ByteArray {
        require(envelope.version == VERSION) { "Unsupported encrypted payload version" }
        require(envelope.algorithm == "A256GCM") { "Unsupported encryption algorithm" }
        val nonce = java.util.Base64.getDecoder().decode(envelope.nonceBase64)
        val ciphertext = java.util.Base64.getDecoder().decode(envelope.ciphertextBase64)
        require(nonce.size == NONCE_BYTES) { "Invalid nonce" }
        require(ciphertext.size <= MAX_PLAINTEXT_BYTES + TAG_BYTES) { "Payload too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    fun destroy() {
        key.fill(0)
    }

    companion object {
        const val VERSION = 1
        const val MAX_PLAINTEXT_BYTES = 48 * 1024
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
    }
}
