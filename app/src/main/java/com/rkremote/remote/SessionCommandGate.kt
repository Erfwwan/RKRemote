package com.rkremote.remote

import java.util.concurrent.ConcurrentHashMap

/**
 * Authorization boundary for inbound requests.
 *
 * A network message is never an authorization by itself. The local owner must
 * explicitly activate a session and grant each capability. This gate also
 * rejects replayed or out-of-order message sequence numbers per peer.
 */
class SessionCommandGate {
    data class Decision(val allowed: Boolean, val reason: String)

    private val grants = ConcurrentHashMap<String, Set<RemoteCapability>>()
    private val lastSequence = ConcurrentHashMap<String, Long>()

    @Volatile private var sessionActive = false
    @Volatile private var approvedPeerId: String? = null

    @Synchronized
    fun beginOwnerApprovedSession(peerId: String, capabilities: Set<RemoteCapability>) {
        require(peerId.isNotBlank() && peerId.length <= 128)
        require(capabilities.isNotEmpty()) { "At least one owner-approved capability is required" }
        // A gate represents exactly one active peer session. Drop any stale
        // state before binding it to the newly authenticated peer.
        grants.clear()
        lastSequence.clear()
        approvedPeerId = peerId
        grants[peerId] = capabilities.toSet()
        lastSequence[peerId] = -1L
        sessionActive = true
    }

    @Synchronized
    fun grantCapabilities(capabilities: Set<RemoteCapability>) {
        check(sessionActive) { "No owner-approved active session" }
        val peerId = approvedPeerId ?: error("No approved peer")
        grants[peerId] = (grants[peerId] ?: emptySet()) + capabilities
    }

    @Synchronized
    fun revokeCapabilities(capabilities: Set<RemoteCapability>) {
        if (!sessionActive || capabilities.isEmpty()) return
        val peerId = approvedPeerId ?: return
        val remaining = (grants[peerId] ?: emptySet()) - capabilities
        if (remaining.isEmpty()) grants.remove(peerId) else grants[peerId] = remaining
    }

    @Synchronized
    fun endSession() {
        sessionActive = false
        approvedPeerId?.let {
            grants.remove(it)
            lastSequence.remove(it)
        }
        approvedPeerId = null
    }

    @Synchronized
    fun authorize(peerId: String, sequence: Long, capability: RemoteCapability): Decision {
        if (!sessionActive) return Decision(false, "No owner-approved active session")
        if (peerId != approvedPeerId) return Decision(false, "Peer is not paired for this session")
        if (sequence < 0) return Decision(false, "Invalid sequence")
        val previous = lastSequence[peerId] ?: -1L
        if (sequence <= previous) return Decision(false, "Replayed or out-of-order message")
        // Consume every authenticated command sequence, including commands
        // denied by capability policy. A later grant cannot revive an older
        // denied request or permit a lower sequence.
        lastSequence[peerId] = sequence
        if (capability !in (grants[peerId] ?: emptySet())) {
            return Decision(false, "Capability was not approved by the device owner")
        }
        return Decision(true, "Approved")
    }

    @Synchronized
    fun hasCapability(peerId: String, capability: RemoteCapability): Boolean =
        sessionActive && peerId == approvedPeerId && capability in (grants[peerId] ?: emptySet())

    fun isSessionActive(): Boolean = sessionActive
}
