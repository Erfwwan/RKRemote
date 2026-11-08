package com.rkremote.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager

/**
 * Android notifies the app when the global location switch or providers change.
 * We enqueue a one-shot sample; the worker still checks the owner's tracking
 * toggle and the current Android permissions before reading anything.
 */
class LocationStateChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == LocationManager.PROVIDERS_CHANGED_ACTION ||
            action == "android.location.MODE_CHANGED"
        ) {
            LocationCaptureScheduler.enqueueImmediate(context)
        }
    }
}
