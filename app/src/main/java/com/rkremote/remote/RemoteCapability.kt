package com.rkremote.remote

/**
 * Explicit owner-granted capabilities for an authenticated remote peer.
 * Location is deliberately separate from contacts, call logs and media.
 */
enum class RemoteCapability {
    CAMERA,
    MICROPHONE,
    PUSH_TO_TALK,
    CONTACTS,
    ADDRESS_BOOK,
    CALL_LOG,
    LOCATION,
    NOTIFICATIONS,
    FILES,
    SCREEN_VIEW,
    SCREEN_INPUT
}
