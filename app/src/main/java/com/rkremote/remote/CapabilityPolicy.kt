package com.rkremote.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Central permission checks. A granted Android permission is necessary but
 * never sufficient: callers must also require an active, owner-approved session.
 */
object CapabilityPolicy {
    /** Approximate OR precise location is sufficient for location access. */
    fun hasAnyLocationPermission(hasCoarse: Boolean, hasFine: Boolean): Boolean =
        hasCoarse || hasFine

    fun hasRuntimePermission(context: Context, capability: RemoteCapability): Boolean {
        val required = when (capability) {
            RemoteCapability.CAMERA -> listOf(Manifest.permission.CAMERA)
            RemoteCapability.MICROPHONE, RemoteCapability.PUSH_TO_TALK ->
                listOf(Manifest.permission.RECORD_AUDIO)
            RemoteCapability.CONTACTS -> listOf(Manifest.permission.READ_SMS)
            RemoteCapability.ADDRESS_BOOK -> listOf(Manifest.permission.READ_CONTACTS)
            RemoteCapability.CALL_LOG -> listOf(Manifest.permission.READ_CALL_LOG)
            RemoteCapability.LOCATION -> return hasAnyLocationPermission(
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            )
            // Reading other apps' notifications uses the special Notification Access
            // setting, not POST_NOTIFICATIONS (which controls this app's own notifications).
            RemoteCapability.NOTIFICATIONS -> emptyList()
            RemoteCapability.FILES -> emptyList() // SAF URI grant is checked by the file repository.
            RemoteCapability.SCREEN_VIEW, RemoteCapability.SCREEN_INPUT -> emptyList()
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasNotificationListenerAccess(context: Context): Boolean {
        return SecureNotificationSync.isNotificationAccessEnabled(context)
    }
}
