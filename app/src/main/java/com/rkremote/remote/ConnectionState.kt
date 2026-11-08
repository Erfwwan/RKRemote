package com.rkremote.remote

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val peerId: String) : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}
