package com.rkremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small bounded event store for the first milestone.
 *
 * The JSON event list is encrypted with an AES-256-GCM key held by Android
 * Keystore. The key is non-exportable and never written to source control.
 */
internal object EncryptedNotificationStore {
    private const val TAG = "RKRemoteEventStore"
    private const val PREFS = "rkremote_notification_events"
    private const val DATA_KEY = "encrypted_events"
    private const val LAST_EVENT_ID_KEY = "last_event_id"
    private const val KEY_ALIAS = "rkremote_notification_aes_v1"
    private const val MAX_EVENTS = 500
    private const val GCM_TAG_BITS = 128

    @Synchronized
    fun append(context: Context, event: JSONObject) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previousId = prefs.getLong(LAST_EVENT_ID_KEY, 0L)
            val nextId = if (previousId == Long.MAX_VALUE) 1L else previousId + 1L
            val storedEvent = JSONObject(event.toString()).put("eventId", nextId)
            val events = readEvents(prefs.getString(DATA_KEY, null)).apply {
                put(storedEvent)
            }
            val bounded = JSONArray()
            val first = (events.length() - MAX_EVENTS).coerceAtLeast(0)
            for (index in first until events.length()) {
                bounded.put(events.getJSONObject(index))
            }

            val encrypted = encrypt(bounded.toString().toByteArray(Charsets.UTF_8))
            val packed = encrypted.first + encrypted.second
            val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
            check(prefs.edit()
                .putString(DATA_KEY, encoded)
                .putLong(LAST_EVENT_ID_KEY, nextId)
                .commit()) {
                "Could not persist notification event store"
            }
        } catch (error: Exception) {
            Log.e(TAG, "Encrypted notification storage failed", error)
        }
    }

    @Synchronized
    fun readAll(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return readEvents(prefs.getString(DATA_KEY, null))
    }

    private fun readEvents(encoded: String?): JSONArray {
        if (encoded.isNullOrBlank()) return JSONArray()
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 12) { "Invalid encrypted event payload" }
            val iv = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            JSONArray(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (error: Exception) {
            Log.e(TAG, "Could not decrypt notification event store", error)
            JSONArray()
        }
    }

    private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv to cipher.doFinal(plaintext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
