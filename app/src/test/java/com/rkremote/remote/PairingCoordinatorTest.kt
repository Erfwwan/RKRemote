package com.rkremote.remote

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCoordinatorTest {
    @Test
    fun challengeIsSingleUseAndExpires() {
        var clock = 1_000L
        val coordinator = PairingCoordinator(now = { clock })
        val challenge = coordinator.createChallenge()

        assertNull(coordinator.approve("000000", "controller-a"))
        assertNull(coordinator.approve(challenge.code, "controller-a"))

        val next = coordinator.createChallenge()
        clock = next.expiresAtMillis + 1
        assertNull(coordinator.approve(next.code, "controller-a"))
    }

    @Test
    fun validCodeDerivesNonEmptyKey() {
        val coordinator = PairingCoordinator()
        val challenge = coordinator.createChallenge()
        val result = coordinator.approve(challenge.code, "controller-a")

        assertNotNull(result)
        assertTrue(result!!.sessionKey.size > 16)
        assertTrue(result.controllerId == "controller-a")
    }
}
