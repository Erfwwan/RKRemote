package com.rkremote.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Central permission checks. A granted Android permission is necessary but
 * never sufficient: callers must also require an active, owner-approved session.
 */
object CapabilityPolicy {
    fun hasRuntimePermission(context: Context, capability: RemoteCapability): Boolean {
        val required = when (capability) {
            RemoteCapability.CAMERA -> listOf(Manifest.permission.CAMERA)
            RemoteCapability.MICROPHONE, RemoteCapability.PUSH_TO_TALK ->
                listOf(Manifest.permission.RECORD_AUDIO)
            RemoteCapability.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
            RemoteCapability.CALL_LOG -> listOf(Manifest.permission.READ_CALL_LOG)
            RemoteCapability.LOCATION -> listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ).filter { it == Manifest.permission.ACCESS_COARSE_LOCATION ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED }
            RemoteCapability.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33)
                listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            RemoteCapability.FILES -> emptyList() // SAF URI grant is checked by the file repository.
            RemoteCapability.SCREEN_VIEW, RemoteCapability.SCREEN_INPUT -> emptyList()
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasNotificationListenerAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = context.packageName + "/" + context.packageName + ".NotificationCaptureService"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
