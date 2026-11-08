package com.rkremote.remote

/** Deterministic bounded retry schedule for idempotent connection probes. */
internal object ReconnectPolicy {
    fun delayMillis(consecutiveFailures: Int): Long = when {
        consecutiveFailures <= 0 -> 5_000L
        consecutiveFailures == 1 -> 1_000L
        consecutiveFailures == 2 -> 2_000L
        consecutiveFailures == 3 -> 4_000L
        consecutiveFailures == 4 -> 8_000L
        else -> 15_000L
    }
}
