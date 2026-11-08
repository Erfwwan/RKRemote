package com.rkremote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class P2pPeerRecordTest {
    private fun newKeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    @Test
    fun signedRecordRoundTripsAndVerifies() {
        val now = 1_800_000_000_000L
        val record = P2pPeerRecord.create(
            newKeyPair(),
            sequence = 7,
            addresses = listOf("/ip4/203.0.113.10/tcp/41000"),
            nowMillis = now
        )
        val decoded = P2pPeerRecord.parse(record.toJson().toString(), now)
        assertEquals(record.peerId, decoded.peerId)
        assertEquals(7L, decoded.sequence)
        assertTrue(decoded.verify(now))
    }

    @Test
    fun rejectsTamperedAddress() {
        val now = 1_800_000_000_000L
        val record = P2pPeerRecord.create(newKeyPair(), 1, emptyList(), now)
        val tampered = record.copy(addresses = listOf("/ip4/203.0.113.11/tcp/41000"))
        assertFalse(tampered.verify(now))
    }

    @Test
    fun rejectsExpiredAndOverlongRecords() {
        val now = 1_800_000_000_000L
        val record = P2pPeerRecord.create(newKeyPair(), 1, emptyList(), now)
        assertFalse(record.verify(now + P2pPeerRecord.MAX_TTL_MS + 1))
        assertThrows(IllegalArgumentException::class.java) {
            P2pPeerRecord.create(newKeyPair(), 1, emptyList(), now, P2pPeerRecord.MAX_TTL_MS + 1)
        }
    }

    @Test
    fun rejectsNonTransportAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            P2pPeerRecord.create(
                newKeyPair(), 1, listOf("https://example.invalid/path"), 1_800_000_000_000L
            )
        }
    }
}
