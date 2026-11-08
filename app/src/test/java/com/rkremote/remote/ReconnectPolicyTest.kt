package com.rkremote.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun retriesUseBoundedExponentialBackoff() {
        assertEquals(1_000L, ReconnectPolicy.delayMillis(1))
        assertEquals(2_000L, ReconnectPolicy.delayMillis(2))
        assertEquals(4_000L, ReconnectPolicy.delayMillis(3))
        assertEquals(8_000L, ReconnectPolicy.delayMillis(4))
        assertEquals(15_000L, ReconnectPolicy.delayMillis(5))
        assertEquals(15_000L, ReconnectPolicy.delayMillis(20))
    }

    @Test
    fun healthyConnectionUsesFiveSecondProbeInterval() {
        assertEquals(5_000L, ReconnectPolicy.delayMillis(0))
    }

    @Test
    fun negativeFailureCountIsTreatedAsHealthy() {
        assertEquals(5_000L, ReconnectPolicy.delayMillis(-1))
    }
}
