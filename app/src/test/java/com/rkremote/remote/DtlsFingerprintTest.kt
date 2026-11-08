package com.rkremote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DtlsFingerprintTest {
    private val valid = (0 until 32).joinToString(":") { "%02X".format(it) }

    @Test
    fun normalizesAndExtractsOneConsistentFingerprint() {
        val sdp = """
            v=0
            a=fingerprint:sha-256 $valid
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            a=fingerprint:sha-256 ${valid.lowercase()}
        """.trimIndent()
        assertEquals(valid, DtlsFingerprint.extractSha256(sdp))
        assertEquals(valid, DtlsFingerprint.normalize(valid.lowercase()))
    }

    @Test
    fun rejectsMissingMalformedAndInconsistentFingerprints() {
        assertThrows(IllegalArgumentException::class.java) {
            DtlsFingerprint.extractSha256("v=0\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DtlsFingerprint.normalize("AA:BB")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DtlsFingerprint.normalize("GG:" + "AA:".repeat(30) + "AA")
        }
        val other = "FF:" + valid.substring(3)
        assertThrows(IllegalArgumentException::class.java) {
            DtlsFingerprint.extractSha256(
                "a=fingerprint:sha-256 $valid\na=fingerprint:sha-256 $other"
            )
        }
    }

    @Test
    fun rejectsEnvelopeFingerprintMismatch() {
        val other = "FF:" + valid.substring(3)
        assertThrows(IllegalArgumentException::class.java) {
            DtlsFingerprint.requireMatches("a=fingerprint:sha-256 $valid", other)
        }
    }
}
