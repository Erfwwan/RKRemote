package com.rkremote.remote

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import com.stonygrey.torann.NoiseProtocol
import com.stonygrey.torann.KeyPair as NoiseKeyPair
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the Noise X25519 static private key as AES-GCM ciphertext.
 * The AES wrapping key is non-exportable in Android Keystore. The X25519 key itself
 * is not claimed to be hardware-backed: it exists briefly in app memory during a session.
 *
 * Torann's Android implementation requires API 33+, so this store fails closed below it.
 */
@RequiresApi(33)
class SecureLinkStaticKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    @Synchronized
    fun getOrCreate(): StoredKey {
        require(Build.VERSION.SDK_INT >= 33) { "SecureLink requires Android 13 (API 33) or later" }
        val protocol = NoiseProtocol.parse(PROTOCOL_NAME)
        val encrypted = preferences.getString(PRIVATE_KEY_CIPHERTEXT, null)
        val iv = preferences.getString(PRIVATE_KEY_IV, null)
        val generation = preferences.getLong(KEY_GENERATION, 0L)

        if (encrypted != null && iv != null && generation > 0L) {
            val privateBytes = decrypt(
                Base64.getDecoder().decode(iv),
                Base64.getDecoder().decode(encrypted)
            )
            return try {
                StoredKey(protocol.createKeyPair(privateBytes), generation)
            } finally {
                privateBytes.fill(0)
            }
        }

        val generated = protocol.generateKeyPair()
        val privateBytes = generated.privateKey
        return try {
            val (nonce, ciphertext) = encrypt(privateBytes)
            check(preferences.edit()
                .putString(PRIVATE_KEY_IV, Base64.getEncoder().encodeToString(nonce))
                .putString(PRIVATE_KEY_CIPHERTEXT, Base64.getEncoder().encodeToString(ciphertext))
                .putLong(KEY_GENERATION, 1L)
                .commit()) { "Unable to persist the encrypted Noise identity" }
            StoredKey(protocol.createKeyPair(privateBytes), 1L)
        } finally {
            privateBytes.fill(0)
            generated.close()
        }
    }

    @Synchronized
    fun rotate(): StoredKey {
        val current = preferences.getLong(KEY_GENERATION, 0L)
        require(current < Long.MAX_VALUE) { "Noise key generation exhausted" }
        val protocol = NoiseProtocol.parse(PROTOCOL_NAME)
        val generated = protocol.generateKeyPair()
        val privateBytes = generated.privateKey
        return try {
            val (nonce, ciphertext) = encrypt(privateBytes)
            val next = current + 1L
            check(preferences.edit()
                .putString(PRIVATE_KEY_IV, Base64.getEncoder().encodeToString(nonce))
                .putString(PRIVATE_KEY_CIPHERTEXT, Base64.getEncoder().encodeToString(ciphertext))
                .putLong(KEY_GENERATION, next)
                .commit()) { "Unable to rotate the encrypted Noise identity" }
            StoredKey(protocol.createKeyPair(privateBytes), next)
        } finally {
            privateBytes.fill(0)
            generated.close()
        }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit()
            .remove(PRIVATE_KEY_IV)
            .remove(PRIVATE_KEY_CIPHERTEXT)
            .remove(KEY_GENERATION)
            .commit()) { "Unable to clear the Noise identity" }
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = store.getKey(WRAPPING_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAPPING_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(AAD)
        return cipher.iv to cipher.doFinal(plaintext)
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == GCM_NONCE_BYTES && ciphertext.size == X25519_PRIVATE_KEY_BYTES + GCM_TAG_BYTES) {
            "Stored Noise key has an invalid size"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
    }

    data class StoredKey(val keyPair: NoiseKeyPair, val generation: Long)

    companion object {
        const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val WRAPPING_ALIAS = "rkremote.securelink.x25519.wrap.v1"
        private const val PREFS_NAME = "rkremote_securelink_identity"
        private const val PRIVATE_KEY_IV = "noise_static_iv"
        private const val PRIVATE_KEY_CIPHERTEXT = "noise_static_ciphertext"
        private const val KEY_GENERATION = "noise_static_generation"
        private const val X25519_PRIVATE_KEY_BYTES = 32
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private val AAD = "RKRemote-SecureLink-static-key-v1".toByteArray(Charsets.UTF_8)
    }
}
