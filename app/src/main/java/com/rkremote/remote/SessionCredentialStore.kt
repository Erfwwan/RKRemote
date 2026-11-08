package com.rkremote.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only the active LAN session token, encrypted with an Android Keystore key.
 * The token is retained only while the owner-approved session is active.
 */
object SessionCredentialStore {
    private const val PREFS = "rkremote_session"
    private const val KEY_ALIAS = "rkremote_session_token_v1"
    private const val TOKEN_KEY = "encrypted_session_token"
    private const val ACTIVE_KEY = "session_active"
    private const val BOOT_KEY = "auto_start_after_boot"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    @Synchronized
    fun saveActiveSession(context: Context, token: String) {
        require(token.length >= 24) { "Session token is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(cipher.iv.size + encrypted.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        prefs(context).edit()
            .putString(TOKEN_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
            .putBoolean(ACTIVE_KEY, true)
            .apply()
    }

    @Synchronized
    fun activeToken(context: Context): String? {
        val preferences = prefs(context)
        if (!preferences.getBoolean(ACTIVE_KEY, false)) return null
        return runCatching {
            val packed = Base64.decode(
                preferences.getString(TOKEN_KEY, null) ?: return null,
                Base64.NO_WRAP
            )
            require(packed.size > IV_BYTES)
            val buffer = ByteBuffer.wrap(packed)
            val iv = ByteArray(IV_BYTES).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8).takeIf { it.length >= 24 }
        }.getOrNull()
    }

    fun isAutoStartAfterBoot(context: Context): Boolean =
        prefs(context).getBoolean(BOOT_KEY, false)

    fun setAutoStartAfterBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(BOOT_KEY, enabled).apply()
    }

    fun clearActiveSession(context: Context) {
        prefs(context).edit()
            .putBoolean(ACTIVE_KEY, false)
            .remove(TOKEN_KEY)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
