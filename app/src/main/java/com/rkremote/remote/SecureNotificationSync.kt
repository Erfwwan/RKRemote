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
    internal const val MAX_RESPONSE_BYTES = 48 * 1024

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
        val start = (stored.length() - MAX_EVENTS_PER_RESPONSE).coerceAtLeast(0)
        val events = buildList {
            for (index in start until stored.length()) {
                val event = stored.optJSONObject(index) ?: continue
                add(JSONObject()
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
        }
        return buildBoundedResponse(requestId, stored.length(), events)
    }

    /** Pure bounded response builder so payload size can be unit-tested without Android services. */
    internal fun buildBoundedResponse(
        requestId: String,
        available: Int,
        events: List<JSONObject>
    ): JSONObject {
        require(requestId.isNotBlank() && requestId.length <= 128)
        require(available >= 0)
        val result = JSONArray()
        for (event in events.take(MAX_EVENTS_PER_RESPONSE)) {
            result.put(event)
            if (response(requestId, available, result).toString()
                    .toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES
            ) {
                result.remove(result.length() - 1)
                break
            }
        }
        return response(requestId, available, result).also { response ->
            check(response.toString().toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
                "Notification response exceeds the secure message size limit"
            }
        }
    }

    private fun response(requestId: String, available: Int, events: JSONArray): JSONObject =
        JSONObject()
            .put("requestId", requestId)
            .put("events", events)
            .put("returned", events.length())
            .put("available", available)
            .put("truncated", available > events.length())
}
