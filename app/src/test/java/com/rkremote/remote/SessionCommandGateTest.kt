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

    @Test fun incrementalGrantPreservesSequenceAndExistingCapabilities() {
        val gate = SessionCommandGate()
        gate.beginOwnerApprovedSession("peer", setOf(RemoteCapability.SCREEN_VIEW))
        assertTrue(gate.authorize("peer", 5, RemoteCapability.SCREEN_VIEW).allowed)
        gate.grantCapabilities(setOf(RemoteCapability.SCREEN_INPUT))
        assertTrue(gate.authorize("peer", 6, RemoteCapability.SCREEN_INPUT).allowed)
        assertTrue(gate.authorize("peer", 7, RemoteCapability.SCREEN_VIEW).allowed)
        assertFalse(gate.authorize("peer", 5, RemoteCapability.SCREEN_INPUT).allowed)
    }

    @Test fun deniedCapabilityStillConsumesItsAuthenticatedSequence() {
        val gate = SessionCommandGate()
        gate.beginOwnerApprovedSession("peer", setOf(RemoteCapability.SCREEN_VIEW))
        assertFalse(gate.authorize("peer", 10, RemoteCapability.CAMERA).allowed)
        gate.grantCapabilities(setOf(RemoteCapability.CAMERA))
        assertFalse(gate.authorize("peer", 9, RemoteCapability.CAMERA).allowed)
        assertTrue(gate.authorize("peer", 11, RemoteCapability.CAMERA).allowed)
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
