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
        "screen.video.frame",
        "screen.video.end",
        "audio.chunk"
    )

    private val controllerToTarget = setOf("ptt.audio.chunk")

    fun isAllowedIncoming(role: CloudflareSignalingClient.Role, messageType: String): Boolean =
        when {
            messageType in targetToController ->
                role == CloudflareSignalingClient.Role.CONTROLLER
            messageType in controllerToTarget ->
                role == CloudflareSignalingClient.Role.TARGET
            else -> true
        }
}
