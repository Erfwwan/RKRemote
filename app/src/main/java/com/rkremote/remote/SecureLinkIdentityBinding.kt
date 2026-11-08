package com.rkremote.remote

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.Signature
import java.util.Base64

/**
 * P-256-signed binding between the durable RKRemote peer identity and a Noise X25519 static key.
 * The binding is carried only inside encrypted Noise XX handshake payloads.
 */
data class SecureLinkIdentityBinding(
    val version: Int,
    val generation: Long,
    val peerId: String,
    val signingPublicKeyBase64: String,
    val noiseStaticPublicKeyBase64: String,
    val signatureBase64: String
) {
    fun verify(): Boolean {
        if (version != VERSION || generation < 1L) return false
        if (!peerId.matches(Regex("[a-f0-9]{64}"))) return false
        val signingBytes = runCatching { Base64.getDecoder().decode(signingPublicKeyBase64) }.getOrNull()
            ?: return false
        val noiseBytes = runCatching { Base64.getDecoder().decode(noiseStaticPublicKeyBase64) }.getOrNull()
            ?: return false
        val signatureBytes = runCatching { Base64.getDecoder().decode(signatureBase64) }.getOrNull()
            ?: return false
        if (noiseBytes.size != X25519_PUBLIC_KEY_BYTES) return false
        if (P2pPeerRecord.peerIdFor(signingBytes) != peerId) return false
        return runCatching {
            val publicKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(signingBytes))
            Signature.getInstance(P2pPeerRecord.SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(canonicalBytes())
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }

    fun toJson(): String = JSONObject()
        .put("v", version)
        .put("generation", generation)
        .put("peerId", peerId)
        .put("signingPublicKey", signingPublicKeyBase64)
        .put("noiseStaticPublicKey", noiseStaticPublicKeyBase64)
        .put("signature", signatureBase64)
        .toString()

    private fun canonicalBytes(): ByteArray = canonical(
        version, generation, peerId, signingPublicKeyBase64, noiseStaticPublicKeyBase64
    )

    companion object {
        const val VERSION = 1
        const val X25519_PUBLIC_KEY_BYTES = 32
        const val MAX_BINDING_BYTES = 4096
        private const val DOMAIN = "RK-SecureLink-KeyBinding-v1"

        fun create(
            identityStore: P2pIdentityStore,
            noiseStaticPublicKey: ByteArray,
            generation: Long
        ): SecureLinkIdentityBinding {
            require(noiseStaticPublicKey.size == X25519_PUBLIC_KEY_BYTES)
            require(generation >= 1L)
            val identity = identityStore.getOrCreateIdentity()
            val noiseBase64 = Base64.getEncoder().withoutPadding()
                .encodeToString(noiseStaticPublicKey)
            val unsigned = canonical(
                VERSION, generation, identity.peerId, identity.publicKeyBase64, noiseBase64
            )
            val signature = identityStore.sign(unsigned)
            return SecureLinkIdentityBinding(
                VERSION,
                generation,
                identity.peerId,
                identity.publicKeyBase64,
                noiseBase64,
                Base64.getEncoder().withoutPadding().encodeToString(signature)
            )
        }

        fun parse(raw: ByteArray): SecureLinkIdentityBinding {
            require(raw.size in 1..MAX_BINDING_BYTES) { "Invalid peer binding size" }
            val json = JSONObject(raw.toString(Charsets.UTF_8))
            val binding = SecureLinkIdentityBinding(
                json.getInt("v"),
                json.getLong("generation"),
                json.getString("peerId"),
                json.getString("signingPublicKey"),
                json.getString("noiseStaticPublicKey"),
                json.getString("signature")
            )
            require(binding.verify()) { "Peer identity binding signature is invalid" }
            return binding
        }

        private fun canonical(
            version: Int,
            generation: Long,
            peerId: String,
            signingPublicKeyBase64: String,
            noiseStaticPublicKeyBase64: String
        ): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeUTF(DOMAIN)
                out.writeInt(version)
                out.writeLong(generation)
                out.writeUTF(peerId)
                out.writeUTF(signingPublicKeyBase64)
                out.writeUTF(noiseStaticPublicKeyBase64)
            }
            return bytes.toByteArray()
        }
    }
}
