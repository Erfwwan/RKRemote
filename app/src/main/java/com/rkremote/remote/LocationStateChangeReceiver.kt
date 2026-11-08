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
            if (LocationHistoryStore(context).isTrackingEnabled()) {\n                val enabled = runCatching {\n                    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager\n                    if (android.os.Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled\n                    else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)\n                }.getOrDefault(false)\n                val previous = LocationHistoryStore(context).latestValidFix()\n                LocationHistoryStore(context).append(\n                    LocationSnapshot(\n                        recordedAtMillis = System.currentTimeMillis(),\n                        locationEnabled = enabled,\n                        latitude = previous?.latitude,\n                        longitude = previous?.longitude,\n                        accuracyMeters = previous?.accuracyMeters,\n                        fixTimeMillis = previous?.fixTimeMillis,\n                        freshFix = false\n                    )\n                )\n                LocationCaptureScheduler.enqueueImmediate(context)\n            }
        }
    }
}
