package com.rkremote.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun successfulProbeUsesFiveSecondHealthInterval() {
        assertEquals(5_000L, ReconnectPolicy.delayMillis(0))
    }

    @Test fun failuresBackOffAndRemainBounded() {
        assertEquals(1_000L, ReconnectPolicy.delayMillis(1))
        assertEquals(2_000L, ReconnectPolicy.delayMillis(2))
        assertEquals(4_000L, ReconnectPolicy.delayMillis(3))
        assertEquals(8_000L, ReconnectPolicy.delayMillis(4))
        assertEquals(15_000L, ReconnectPolicy.delayMillis(5))
        assertEquals(15_000L, ReconnectPolicy.delayMillis(100))
    }
}
