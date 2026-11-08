package com.rkremote.remote

/**
 * Application response direction guard. Commands are authorized by the target
 * command router; response/data messages must only be consumed by their
 * intended receiving role.
 */
internal object RemoteMessageDirection {
    private val targetToController = setOf(
        "camera.frame.begin",
        "camera.frame.chunk",
        "camera.frame.complete",
        "file.list.result",
        "command.result",
        "file.download.begin",
        "file.download.chunk",
        "file.download.complete",
        "file.download.error",
        "sms.sync.result",
        "location.sync.result",
        "contacts.sync.result",
        "calllog.sync.result",
        "notifications.sync.result",
        "screen.video.config",
        "screen.video.chunk",
        "screen.video.end",
        "audio.chunk",
        "microphone.audio.chunk",
        "microphone.stream.ended"
    )

    // Every command registered by SecureRemoteCommandRouter is controller-originated.
    // Keep this list explicit so a controller cannot accidentally consume a command
    // echoed or injected back through a compromised/misconfigured relay.
    private val controllerToTarget = setOf(
        "screen.stream.start",
        "screen.stream.stop",
        "screen.input",
        "file.list.request",
        "file.download.request",
        "file.transfer.request",
        "file.upload.begin",
        "file.transfer.chunk",
        "file.transfer.complete",
        "file.transfer.cancel",
        "notifications.sync.request",
        "calllog.sync.request",
        "sms.sync.request",
        "contacts.sync.request",
        "location.sync.request",
        "camera.snapshot.request",
        "ptt.start",
        "ptt.stop",
        "ptt.audio.chunk",
        "microphone.stream.start",
        "microphone.stream.stop"
    )

    fun isAllowedIncoming(role: CloudflareSignalingClient.Role, messageType: String): Boolean =
        when {
            messageType in targetToController ->
                role == CloudflareSignalingClient.Role.CONTROLLER
            messageType in controllerToTarget ->
                role == CloudflareSignalingClient.Role.TARGET
            else -> true
        }
}
