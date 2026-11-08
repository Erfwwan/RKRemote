package com.rkremote.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPolicyTest {
    @Test
    fun locationAccessAcceptsEitherApproximateOrPreciseGrant() {
        assertFalse(CapabilityPolicy.hasAnyLocationPermission(false, false))
        assertTrue(CapabilityPolicy.hasAnyLocationPermission(true, false))
        assertTrue(CapabilityPolicy.hasAnyLocationPermission(false, true))
        assertTrue(CapabilityPolicy.hasAnyLocationPermission(true, true))
    }
}
