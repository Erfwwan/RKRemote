package com.rkremote.remote

import java.util.LinkedHashMap

/**
 * Target-side authorization and dispatch boundary for Internet P2P commands.
 *
 * It deliberately has no Android service side effects of its own. Feature
 * handlers must independently verify local service state, Android permissions,
 * SAF grants and any per-operation constraints before acting.
 */
class SecureRemoteCommandRouter(
    private val gate: SessionCommandGate = SessionCommandGate(),
    private val maxRememberedRequests: Int = 512
) {
    data class Result(
        val accepted: Boolean,
        val reason: String,
        val responseType: String? = null
    )

    fun interface Handler {
        fun handle(message: RemoteMessage): String?
    }

    private data class Route(
        val capability: RemoteCapability,
        val handler: Handler
    )

    private val routes = LinkedHashMap<String, Route>()
    private val seenRequests = object : LinkedHashMap<String, Boolean>(maxRememberedRequests, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > maxRememberedRequests
    }

    init {
        require(maxRememberedRequests in 1..4096)
    }

    @Synchronized
    fun register(type: String, capability: RemoteCapability, handler: Handler) {
        require(type in COMMAND_CAPABILITIES) { "Message type is not a remote command" }
        routes[type] = Route(capability, handler)
    }

    @Synchronized
    fun beginOwnerApprovedSession(peerId: String, capabilities: Set<RemoteCapability>) {
        gate.beginOwnerApprovedSession(peerId, capabilities)
        seenRequests.clear()
    }

    @Synchronized
    fun hasActiveSession(): Boolean = gate.isSessionActive()

    @Synchronized
    fun grantCapabilities(capabilities: Set<RemoteCapability>) {
        require(capabilities.isNotEmpty())
        gate.grantCapabilities(capabilities)
    }

    @Synchronized
    fun endSession() {
        gate.endSession()
        seenRequests.clear()
    }

    @Synchronized
    fun dispatch(authenticatedPeerId: String, message: RemoteMessage): Result {
        val route = routes[message.type]
            ?: return Result(false, "No local handler is registered for this command")
        val requestId = message.payload.optString("requestId", "")
        if (requestId.isBlank() || requestId.length > 128) {
            return Result(false, "A bounded requestId is required")
        }
        if (seenRequests.containsKey(requestId)) {
            return Result(false, "Duplicate requestId")
        }

        val decision = gate.authorize(authenticatedPeerId, message.sequence, route.capability)
        if (!decision.allowed) return Result(false, decision.reason)

        // Remember only after authorization. Handler failure still consumes the
        // sequence and request ID, so an attacker cannot replay a side effect.
        seenRequests[requestId] = true
        return try {
            val responseType = route.handler.handle(message)
            Result(true, "Accepted", responseType)
        } catch (error: Exception) {
            Result(false, error.message?.take(160) ?: "Command handler failed")
        }
    }

    companion object {
        fun isCommandType(type: String): Boolean = type in COMMAND_CAPABILITIES

        private val COMMAND_CAPABILITIES = mapOf(
            "screen.snapshot.request" to RemoteCapability.SCREEN_VIEW,
            "screen.stream.start" to RemoteCapability.SCREEN_VIEW,
            "screen.stream.stop" to RemoteCapability.SCREEN_VIEW,
            "screen.input" to RemoteCapability.SCREEN_INPUT,
            "file.list.request" to RemoteCapability.FILES,
            "file.download.request" to RemoteCapability.FILES,
            "file.transfer.request" to RemoteCapability.FILES,
            "file.upload.begin" to RemoteCapability.FILES,
            "file.transfer.chunk" to RemoteCapability.FILES,
            "file.transfer.complete" to RemoteCapability.FILES,
            "file.transfer.cancel" to RemoteCapability.FILES,
            "notifications.sync.request" to RemoteCapability.NOTIFICATIONS,
            "camera.start.request" to RemoteCapability.CAMERA,
            "camera.stop" to RemoteCapability.CAMERA,
            "audio.start.request" to RemoteCapability.MICROPHONE,
            "audio.stop" to RemoteCapability.MICROPHONE,
            "ptt.start" to RemoteCapability.PUSH_TO_TALK,
            "ptt.stop" to RemoteCapability.PUSH_TO_TALK
        )
    }
}
