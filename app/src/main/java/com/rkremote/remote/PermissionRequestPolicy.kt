package com.rkremote.remote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Shared one-attempt policy for ordinary Android runtime permissions.
 *
 * A denied or dismissed system request is not automatically repeated. The owner
 * can change the grant later in Android App settings. Special permissions
 * (Notification Access, Accessibility, MediaProjection and SAF) use their own
 * Android-controlled consent flows and are intentionally not handled here.
 */
object PermissionRequestPolicy {
    private const val PREFS_NAME = "rkremote_permission_requests"
    private const val PREFIX = "requested_"

    fun wasRequested(context: Context, permissions: Collection<String>): Boolean {
        if (permissions.isEmpty()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return permissions.any { permission ->
            prefs.getBoolean(keyFor(permission), false) ||
                legacyKeyFor(permission)?.let { prefs.getBoolean(PREFIX + it, false) } == true
        }
    }

    fun markRequested(context: Context, permissions: Collection<String>) {
        require(permissions.isNotEmpty())
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(prefs.edit().apply {
            permissions.forEach { permission ->
                putBoolean(keyFor(permission), true)
                legacyKeyFor(permission)?.let { putBoolean(PREFIX + it, true) }
            }
        }.commit()) { "Could not save the permission request state" }
    }

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    private fun keyFor(permission: String): String = PREFIX + permission

    /** Migrate the earlier UI's human-readable preference keys without re-prompting. */
    private fun legacyKeyFor(permission: String): String? = when (permission) {
        Manifest.permission.CAMERA -> "camera"
        Manifest.permission.READ_CONTACTS -> "contacts"
        Manifest.permission.READ_SMS -> "sms"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "location"
        Manifest.permission.READ_CALL_LOG -> "call_log"
        Manifest.permission.RECORD_AUDIO -> "microphone"
        Manifest.permission.POST_NOTIFICATIONS -> "notifications"
        else -> null
    }
}
