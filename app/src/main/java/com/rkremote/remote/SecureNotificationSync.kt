package com.rkremote.remote

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.rkremote.EncryptedNotificationStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only notification export for a paired, owner-approved session.
 * Notification Access must already be enabled locally by the device owner.
 */
object SecureNotificationSync {
    private const val MAX_EVENTS_PER_RESPONSE = 20
    private const val MAX_FIELD_CHARS = 1200

    fun isNotificationAccessEnabled(context: Context): Boolean {
        val expected = ComponentName(context, com.rkremote.NotificationCaptureService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(':').any { raw ->
            ComponentName.unflattenFromString(raw) == expected
        }
    }

    fun buildLatestResponse(context: Context, requestId: String): JSONObject {
        require(requestId.isNotBlank() && requestId.length <= 128)
        check(isNotificationAccessEnabled(context)) {
            "Enable RKRemote Notification Access locally on the target first"
        }
        val stored = EncryptedNotificationStore.readAll(context)
        val result = JSONArray()
        val start = (stored.length() - MAX_EVENTS_PER_RESPONSE).coerceAtLeast(0)
        for (index in start until stored.length()) {
            val event = stored.optJSONObject(index) ?: continue
            result.put(JSONObject()
                .put("eventId", event.optLong("eventId"))
                .put("type", event.optString("type").take(16))
                .put("package", event.optString("package").take(180))
                .put("title", event.optString("title").take(MAX_FIELD_CHARS))
                .put("text", event.optString("text").take(MAX_FIELD_CHARS))
                .put("subText", event.optString("subText").take(300))
                .put("conversation", event.optString("conversation").take(300))
                .put("postedAt", event.optLong("postedAt"))
                .put("capturedAt", event.optLong("capturedAt"))
                .put("notificationId", event.optInt("notificationId"))
            )
        }
        return JSONObject()
            .put("requestId", requestId)
            .put("events", result)
            .put("returned", result.length())
            .put("available", stored.length())
            .put("truncated", stored.length() > result.length())
    }
}
