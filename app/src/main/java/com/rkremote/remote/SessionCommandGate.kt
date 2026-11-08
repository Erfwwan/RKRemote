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
        if (capability !in (grants[peerId] ?: emptySet())) {
            return Decision(false, "Capability was not approved by the device owner")
        }
        val previous = lastSequence[peerId] ?: -1L
        if (sequence <= previous) return Decision(false, "Replayed or out-of-order message")
        lastSequence[peerId] = sequence
        return Decision(true, "Approved")
    }

    fun isSessionActive(): Boolean = sessionActive
}
