package com.rkremote

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

/**
 * Receives notifications only after the device owner explicitly enables
 * RKRemote in Android's Notification Access settings.
 *
 * Events are stored locally in an encrypted app-private store. This service
 * does not transmit notification content to another device.
 */
class NotificationCaptureService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString().orEmpty()

            val event = JSONObject()
                .put("type", "posted")
                .put("package", sbn.packageName)
                .put("title", title)
                .put("text", text)
                .put("subText", subText)
                .put("conversation", conversation)
                .put("postedAt", sbn.postTime)
                .put("capturedAt", System.currentTimeMillis())
                .put("notificationId", sbn.id)
                .put("tag", sbn.tag ?: JSONObject.NULL)

            EncryptedNotificationStore.append(applicationContext, event)
        }.onFailure {
            Log.e(TAG, "Could not capture notification event", it)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching {
            val event = JSONObject()
                .put("type", "removed")
                .put("package", sbn.packageName)
                .put("postedAt", sbn.postTime)
                .put("capturedAt", System.currentTimeMillis())
                .put("notificationId", sbn.id)
                .put("tag", sbn.tag ?: JSONObject.NULL)

            EncryptedNotificationStore.append(applicationContext, event)
        }.onFailure {
            Log.e(TAG, "Could not capture notification removal", it)
        }
    }

    companion object {
        private const val TAG = "RKRemoteNotifications"
    }
}
