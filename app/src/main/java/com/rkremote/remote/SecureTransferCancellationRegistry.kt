package com.rkremote.remote

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks cancellation independently for each active outbound file transfer.
 * Unknown and already-finished IDs cannot cancel a different transfer.
 */
class SecureTransferCancellationRegistry {
    private val active = ConcurrentHashMap<String, AtomicBoolean>()

    fun register(transferId: String): Boolean {
        require(transferId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return active.putIfAbsent(transferId, AtomicBoolean(false)) == null
    }

    fun isCancelled(transferId: String): Boolean =
        active[transferId]?.get() ?: true

    fun cancel(transferId: String): Boolean {
        val token = active[transferId] ?: return false
        return token.compareAndSet(false, true)
    }

    fun finish(transferId: String) {
        active.remove(transferId)
    }

    fun clear() {
        active.values.forEach { it.set(true) }
        active.clear()
    }
}
