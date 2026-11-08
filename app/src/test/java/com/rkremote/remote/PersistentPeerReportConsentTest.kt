package com.rkremote.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPeerReportConsentTest {
    @Test
    fun acceptsOnlyCanonicalSha256PeerIds() {
        assertTrue(PersistentPeerReportConsent.isValidPeerId("a".repeat(64)))
        assertFalse(PersistentPeerReportConsent.isValidPeerId("A".repeat(64)))
        assertFalse(PersistentPeerReportConsent.isValidPeerId("a".repeat(63)))
        assertFalse(PersistentPeerReportConsent.isValidPeerId("a".repeat(65)))
        assertFalse(PersistentPeerReportConsent.isValidPeerId("peer-id"))
    }
}
