package com.rkremote.remote

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * In-memory, one-time pairing challenge. It is intentionally not persisted.
 * A network handshake must exchange the derived key through an authenticated,
 * TLS-pinned channel before a device can be considered paired.
 */
class PairingCoordinator(
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis
) {
    data class Challenge(
        val code: String,
        val expiresAtMillis: Long
    )

    data class ApprovedPairing(
        val controllerId: String,
        val sessionKey: ByteArray,
        val expiresAtMillis: Long
    )

    private var active: Challenge? = null

    @Synchronized
    fun createChallenge(): Challenge {
        val code = (100_000 + random.nextInt(900_000)).toString()
        return Challenge(code, now() + CHALLENGE_TTL_MS).also { active = it }
    }

    @Synchronized
    fun approve(code: String, controllerId: String): ApprovedPairing? {
        val challenge = active ?: return null
        active = null // one attempt only, including failed attempts
        if (now() > challenge.expiresAtMillis) return null
        if (controllerId.isBlank() || controllerId.length > 128) return null

        val supplied = code.toByteArray(Charsets.UTF_8)
        val expected = challenge.code.toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(expected, supplied)) return null

        val salt = ByteArray(16).also(random::nextBytes)
        val spec = PBEKeySpec(challenge.code.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val derived = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        // The salt is prepended so a future authenticated handshake can carry it.
        return ApprovedPairing(controllerId, salt + derived, challenge.expiresAtMillis)
    }

    @Synchronized
    fun revokeChallenge() {
        active = null
    }

    companion object {
        const val CHALLENGE_TTL_MS = 5 * 60 * 1000L
        private const val PBKDF2_ITERATIONS = 210_000
    }
}
