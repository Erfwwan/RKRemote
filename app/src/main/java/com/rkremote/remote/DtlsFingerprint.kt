package com.rkremote.remote

/**
 * Strict parser for the SHA-256 DTLS certificate fingerprint carried in SDP.
 * Every fingerprint attribute must be syntactically valid and identical.
 */
object DtlsFingerprint {
    private val fingerprintPattern = Regex("(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}")

    fun normalize(raw: String): String {
        val value = raw.trim()
        require(fingerprintPattern.matches(value)) {
            "DTLS SHA-256 fingerprint must contain exactly 32 hexadecimal octets"
        }
        return value.uppercase(java.util.Locale.ROOT)
    }

    fun extractSha256(sdp: String): String {
        val attributes = Regex("(?im)^a=fingerprint:sha-256\\s+([^\\r\\n]+?)\\s*$")
            .findAll(sdp)
            .map { it.groupValues[1].trim() }
            .toList()
        require(attributes.isNotEmpty()) { "SDP is missing a SHA-256 DTLS fingerprint" }
        val normalized = attributes.map(::normalize).distinct()
        require(normalized.size == 1) {
            "SDP contains inconsistent SHA-256 DTLS fingerprints"
        }
        return normalized.single()
    }

    fun requireMatches(sdp: String, envelopeFingerprint: String) {
        val fromSdp = extractSha256(sdp)
        val fromEnvelope = normalize(envelopeFingerprint)
        require(java.security.MessageDigest.isEqual(
            fromSdp.toByteArray(Charsets.US_ASCII),
            fromEnvelope.toByteArray(Charsets.US_ASCII)
        )) { "Authenticated DTLS fingerprint does not match SDP fingerprint" }
    }
}
