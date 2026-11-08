package com.rkremote.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Long-lived device identity for peer discovery. The EC private key is
 * generated and retained by Android Keystore and is never exported.
 */
class P2pIdentityStore {
    data class Identity(
        val peerId: String,
        val publicKeyBase64: String
    )

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    @Synchronized
    fun getOrCreateIdentity(): Identity {
        val store = keyStore
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEY_STORE
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKeyPair()
        }

        val certificate = keyStore.getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("P2P identity certificate is unavailable")
        val publicBytes = certificate.publicKey.encoded
        return Identity(
            peerId = P2pPeerRecord.peerIdFor(publicBytes),
            publicKeyBase64 = Base64.getEncoder().withoutPadding().encodeToString(publicBytes)
        )
    }

    @Synchronized
    fun sign(data: ByteArray): ByteArray {
        require(data.size <= P2pPeerRecord.MAX_RECORD_BYTES) { "Signing input is too large" }
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
            ?: throw IllegalStateException("P2P identity has not been created")
        return Signature.getInstance(P2pPeerRecord.SIGNATURE_ALGORITHM).run {
            initSign(privateKey as java.security.PrivateKey)
            update(data)
            sign()
        }
    }

    @Synchronized
    fun verify(publicKeyBase64: String, data: ByteArray, signatureBytes: ByteArray): Boolean {
        if (data.size > P2pPeerRecord.MAX_RECORD_BYTES || signatureBytes.isEmpty()) return false
        return runCatching {
            val publicBytes = Base64.getDecoder().decode(publicKeyBase64)
            val publicKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(publicBytes))
            Signature.getInstance(P2pPeerRecord.SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(data)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }

    @Synchronized
    fun createAdvertisement(
        sequence: Long,
        addresses: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
        ttlMillis: Long = P2pPeerRecord.MAX_TTL_MS
    ): P2pPeerRecord {
        require(sequence >= 0L)
        require(ttlMillis in 1..P2pPeerRecord.MAX_TTL_MS)
        require(addresses.size <= P2pPeerRecord.MAX_ADDRESSES)
        require(addresses.all { address ->
            address.length in 1..256 && address.startsWith("/") &&
                (address.startsWith("/ip4/") || address.startsWith("/ip6/") ||
                    address.startsWith("/dns4/") || address.startsWith("/dns6/")) &&
                (address.contains("/tcp/") || address.contains("/udp/")) &&
                address.none { it.isWhitespace() || it.code < 0x21 }
        }) { "Invalid advertised multiaddress list" }

        val identity = getOrCreateIdentity()
        val unsigned = P2pPeerRecord(
            peerId = identity.peerId,
            publicKeyBase64 = identity.publicKeyBase64,
            sequence = sequence,
            issuedAtMillis = nowMillis,
            expiresAtMillis = nowMillis + ttlMillis,
            addresses = addresses.distinct().sorted(),
            signatureBase64 = ""
        )
        val signature = sign(unsigned.signingBytes())
        return unsigned.copy(
            signatureBase64 = Base64.getEncoder().withoutPadding().encodeToString(signature)
        )
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rkremote.p2p.identity.v1"
    }
}
