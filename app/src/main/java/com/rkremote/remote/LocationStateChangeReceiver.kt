package com.rkremote.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager

/**
 * Records location switch/provider transitions as soon as Android broadcasts them.
 * The periodic worker performs a fresh sample when the system allows it.
 */
class LocationStateChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != LocationManager.PROVIDERS_CHANGED_ACTION &&
            action != "android.location.MODE_CHANGED"
        ) return
        if (!LocationHistoryStore(context).isTrackingEnabled()) return

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        val previous = LocationHistoryStore(context).latestValidFix()
        val transition = LocationSnapshot(
            recordedAtMillis = System.currentTimeMillis(),
            locationEnabled = enabled,
            latitude = previous?.latitude,
            longitude = previous?.longitude,
            accuracyMeters = previous?.accuracyMeters,
            fixTimeMillis = previous?.fixTimeMillis,
            freshFix = false
        )
        LocationHistoryStore(context).append(transition)
        LocationSessionBridge.sendIfOwnerApproved(context, transition)
        LocationCaptureScheduler.enqueueImmediate(context)
    }
}
