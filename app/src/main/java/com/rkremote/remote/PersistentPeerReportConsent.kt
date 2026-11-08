package com.rkremote.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted, owner-managed per-peer grants for reports, location and microphone streaming.
 *
 * Entries are bound to the remote peer's stable, signed P2P identity ID. The
 * store never persists a grant for any other capability.
 */
class PersistentPeerReportConsent(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun capabilitiesFor(peerId: String): Set<RemoteCapability> {
        validatePeerId(peerId)
        return readAll()[peerId].orEmpty().mapNotNull(::parseCapability).toSet()
    }

    @Synchronized
    fun allGrants(): Map<String, Set<RemoteCapability>> =
        readAll().mapValues { (_, names) -> names.mapNotNull(::parseCapability).toSet() }
            .filterValues { it.isNotEmpty() }

    @Synchronized
    fun grant(peerId: String, capability: RemoteCapability) {
        validatePeerId(peerId)
        require(capability in PERSISTABLE_CAPABILITIES) {
            "This capability cannot be remembered"
        }
        val records = readAll()
        check(peerId in records || records.size < MAX_REMEMBERED_PEERS) {
            "The remembered peer limit has been reached; revoke an old peer first"
        }
        val grants = records.getOrPut(peerId) { mutableSetOf() }
        grants.add(capability.name)
        writeAll(records)
    }

    @Synchronized
    fun revoke(peerId: String, capabilities: Set<RemoteCapability>) {
        validatePeerId(peerId)
        require(capabilities.all { it in PERSISTABLE_CAPABILITIES })
        val records = readAll()
        val grants = records[peerId] ?: return
        grants.removeAll(capabilities.map { it.name }.toSet())
        if (grants.isEmpty()) records.remove(peerId)
        writeAll(records)
    }

    private fun readAll(): MutableMap<String, MutableSet<String>> {
        val encoded = prefs.getString(ENCRYPTED_RECORDS, null) ?: return mutableMapOf()
        val packed = Base64.getDecoder().decode(encoded)
        require(packed.size > GCM_IV_BYTES) { "Saved peer permissions are damaged" }
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val json = JSONObject(cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
        val result = mutableMapOf<String, MutableSet<String>>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val peerId = keys.next()
            if (!isValidPeerId(peerId)) continue
            val array = json.optJSONArray(peerId) ?: continue
            val names = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                val name = array.optString(index)
                if (name in PERSISTABLE_NAMES) names.add(name)
            }
            if (names.isNotEmpty()) result[peerId] = names
        }
        return result
    }

    private fun writeAll(records: Map<String, Set<String>>) {
        val json = JSONObject()
        records.toSortedMap().forEach { (peerId, names) ->
            val safeNames = names.filter { it in PERSISTABLE_NAMES }.distinct().sorted()
            if (safeNames.isNotEmpty()) {
                json.put(peerId, org.json.JSONArray(safeNames))
            }
        }
        if (json.length() == 0) {
            check(prefs.edit().remove(ENCRYPTED_RECORDS).commit()) {
                "Could not save peer permission changes"
            }
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ciphertext
        val encoded = Base64.getEncoder().encodeToString(packed)
        check(prefs.edit().putString(ENCRYPTED_RECORDS, encoded).commit()) {
            "Could not save peer permission changes"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
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

    private fun parseCapability(name: String): RemoteCapability? =
        when (name) {
            RemoteCapability.CONTACTS.name -> RemoteCapability.CONTACTS
            RemoteCapability.ADDRESS_BOOK.name -> RemoteCapability.ADDRESS_BOOK
            RemoteCapability.CALL_LOG.name -> RemoteCapability.CALL_LOG
            RemoteCapability.LOCATION.name -> RemoteCapability.LOCATION
            RemoteCapability.MICROPHONE.name -> RemoteCapability.MICROPHONE
            else -> null
        }

    private fun validatePeerId(peerId: String) {
        require(isValidPeerId(peerId)) { "Invalid authenticated peer identity" }
    }

    companion object {
        internal fun isValidPeerId(peerId: String): Boolean =
            peerId.matches(Regex("[0-9a-f]{64}"))

        private const val PREFS_NAME = "rkremote_persistent_peer_report_consent"
        private const val ENCRYPTED_RECORDS = "encrypted_records"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rkremote.peer-report-consent.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_REMEMBERED_PEERS = 100
        private val AAD = "RKRemote|peer-report-consent|v1".toByteArray(Charsets.UTF_8)
        private val PERSISTABLE_CAPABILITIES = setOf(
            RemoteCapability.CONTACTS,
            RemoteCapability.ADDRESS_BOOK,
            RemoteCapability.CALL_LOG,
            RemoteCapability.LOCATION,
            RemoteCapability.MICROPHONE
        )
        private val PERSISTABLE_NAMES = PERSISTABLE_CAPABILITIES.map { it.name }.toSet()
    }
}
