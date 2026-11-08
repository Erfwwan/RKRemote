package com.rkremote.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCommandGateTest {
    @Test fun requiresOwnerApprovedSessionAndGrantedCapability() {
        val gate = SessionCommandGate()
        assertFalse(gate.authorize("peer", 1, RemoteCapability.CAMERA).allowed)
        gate.beginOwnerApprovedSession("peer", setOf(RemoteCapability.NOTIFICATIONS))
        assertFalse(gate.authorize("peer", 1, RemoteCapability.CAMERA).allowed)
        assertTrue(gate.authorize("peer", 2, RemoteCapability.NOTIFICATIONS).allowed)
    }

    @Test fun rejectsWrongPeerReplayAndCommandsAfterStop() {
        val gate = SessionCommandGate()
        gate.beginOwnerApprovedSession("owner-approved-peer", setOf(RemoteCapability.FILES))
        assertFalse(gate.authorize("other-peer", 1, RemoteCapability.FILES).allowed)
        assertTrue(gate.authorize("owner-approved-peer", 3, RemoteCapability.FILES).allowed)
        assertFalse(gate.authorize("owner-approved-peer", 3, RemoteCapability.FILES).allowed)
        gate.endSession()
        assertFalse(gate.authorize("owner-approved-peer", 4, RemoteCapability.FILES).allowed)
    }
}
