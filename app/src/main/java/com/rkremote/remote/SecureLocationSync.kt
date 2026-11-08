package com.rkremote.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * A location sample is explicit about freshness: coordinates may be the last
 * valid fix when the location switch is off or no new fix could be obtained.
 */
data class LocationSnapshot(
    val recordedAtMillis: Long,
    val locationEnabled: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val fixTimeMillis: Long?,
    val freshFix: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("recordedAt", recordedAtMillis)
        .put("locationEnabled", locationEnabled)
        .put("latitude", latitude ?: JSONObject.NULL)
        .put("longitude", longitude ?: JSONObject.NULL)
        .put("accuracyMeters", accuracyMeters ?: JSONObject.NULL)
        .put("fixTime", fixTimeMillis ?: JSONObject.NULL)
        .put("freshFix", freshFix)

    companion object {
        fun fromJson(json: JSONObject) = LocationSnapshot(
            recordedAtMillis = json.getLong("recordedAt"),
            locationEnabled = json.getBoolean("locationEnabled"),
            latitude = json.optNullableDouble("latitude"),
            longitude = json.optNullableDouble("longitude"),
            accuracyMeters = json.optNullableDouble("accuracyMeters")?.toFloat(),
            fixTimeMillis = json.optNullableLong("fixTime"),
            freshFix = json.optBoolean("freshFix", false)
        )
    }
}

private fun JSONObject.optNullableDouble(name: String): Double? =
    if (isNull(name) || !has(name)) null else optDouble(name).takeIf { it.isFinite() }

private fun JSONObject.optNullableLong(name: String): Long? =
    if (isNull(name) || !has(name)) null else optLong(name).takeIf { it > 0L }

/** Encrypted, bounded local location history. */
class LocationHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun append(snapshot: LocationSnapshot) = synchronized(LOCK) {
        val history = readAllUnlocked().toMutableList()
        history.add(snapshot)
        val bounded = history.takeLast(MAX_RECORDS)
        val json = JSONArray()
        bounded.forEach { json.put(it.toJson()) }
        writeEncrypted(json.toString())
    }

    fun readAll(): List<LocationSnapshot> = synchronized(LOCK) {
        readAllUnlocked()
    }

    private fun readAllUnlocked(): List<LocationSnapshot> = runCatching {
        val raw = readEncrypted()
        if (raw == null) {
            emptyList()
        } else {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching { LocationSnapshot.fromJson(array.getJSONObject(index)) }.getOrNull()
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun latestValidFix(): LocationSnapshot? = readAll().lastOrNull {
        it.latitude != null && it.longitude != null && it.fixTimeMillis != null
    }

    fun isTrackingEnabled(): Boolean = prefs.getBoolean(TRACKING_ENABLED, false)

    fun setTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(TRACKING_ENABLED, enabled).apply()
    }

    private fun readEncrypted(): String? {
        val encoded = prefs.getString(DATA, null) ?: return null
        val packed = Base64.getDecoder().decode(encoded)
        require(packed.size > IV_BYTES) { "Location history is damaged" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_BYTES))
        )
        cipher.updateAAD(AAD)
        return cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)).toString(Charsets.UTF_8)
    }

    private fun writeEncrypted(raw: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val packed = cipher.iv + cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(DATA, Base64.getEncoder().encodeToString(packed)).commit()) {
            "Could not save encrypted location history"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val MAX_RECORDS = 500
        private val LOCK = Any()
        private const val PREFS = "rkremote_location_history"
        private const val DATA = "encrypted_history"
        private const val TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_ALIAS = "rkremote.location-history.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "RKRemote|location-history|v1".toByteArray(Charsets.UTF_8)
    }
}

/**
 * Holds only the currently authenticated target-side transport. The worker
 * sends location only while the paired peer has both persistent and live consent.
 */
object SecureLocationSync {
    /**
     * Resolves one fresh location fix for an authenticated, already-authorized
     * controller request. On older Android versions, returns the best last-known
     * fix and marks it stale rather than presenting it as current.
     */
    @Suppress("MissingPermission")
    fun requestCurrentResponse(
        context: Context,
        requestId: String,
        send: (JSONObject) -> Unit
    ) {
        require(requestId.isNotBlank() && requestId.length <= 128)
        val app = context.applicationContext
        val store = LocationHistoryStore(app)
        val hasFine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = runCatching {
            if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        val provider = (if (hasFine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER)).firstOrNull {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        val previous = store.latestValidFix()
        fun respond(location: Location?, freshFix: Boolean = location != null) {
            val now = System.currentTimeMillis()
            val snapshot = if (location != null) {
                LocationSnapshot(now, enabled, location.latitude, location.longitude,
                    if (location.hasAccuracy()) location.accuracy else null, location.time, freshFix)
            } else {
                LocationSnapshot(now, enabled, previous?.latitude, previous?.longitude,
                    previous?.accuracyMeters, previous?.fixTimeMillis, false)
            }
            store.append(snapshot)
            val records = JSONArray().put(snapshot.toJson())
            send(JSONObject().put("requestId", requestId).put("records", records)
                .put("returned", 1).put("trackingEnabled", store.isTrackingEnabled())
                .put("isImmediate", true))
        }
        if ((!hasFine && !hasCoarse) || !enabled || provider == null) {
            respond(null)
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                manager.getCurrentLocation(provider, CancellationSignal(), app.mainExecutor) { location ->
                    respond(location)
                }
            }.onFailure { respond(null) }
        } else {
            @Suppress("DEPRECATION")
            respond(runCatching { manager.getLastKnownLocation(provider) }.getOrNull(), freshFix = false)
        }
    }

    fun buildLatestResponse(context: Context, requestId: String): JSONObject {
        require(requestId.isNotBlank() && requestId.length <= 128)
        val store = LocationHistoryStore(context)
        val records = store.readAll().takeLast(100)
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("requestId", requestId)
            .put("records", array)
            .put("returned", records.size)
            .put("trackingEnabled", store.isTrackingEnabled())
    }
}

object LocationSessionBridge {
    private val activePeer = AtomicReference<SecureInternetPeer?>(null)

    fun attach(peer: SecureInternetPeer) {
        activePeer.set(peer)
    }

    fun detach(peer: SecureInternetPeer) {
        activePeer.compareAndSet(peer, null)
    }

    private fun authorizedPeer(
        context: Context,
        requireBackgroundLocation: Boolean
    ): SecureInternetPeer? {
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) return null
        if (requireBackgroundLocation && Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val peer = activePeer.get() ?: return null
        val peerId = peer.authenticatedPeerId() ?: return null
        val remembered = runCatching {
            PersistentPeerReportConsent(context).capabilitiesFor(peerId)
        }.getOrDefault(emptySet())
        if (RemoteCapability.LOCATION !in remembered ||
            !peer.hasOwnerApprovedCapability(RemoteCapability.LOCATION)
        ) return null
        return peer
    }

    fun sendIfOwnerApproved(context: Context, snapshot: LocationSnapshot): Boolean {
        val peer = authorizedPeer(context, requireBackgroundLocation = true) ?: return false
        return peer.sendMessage(
            "location.sync.result",
            snapshot.toJson().put("requestId", "periodic-${snapshot.recordedAtMillis}")
        )
    }

    fun sendHistoryIfOwnerApproved(context: Context): Boolean {
        // Reconnect history is sent while the app is active; foreground location
        // permission is sufficient. Background permission is reserved for workers
        // and receiver-triggered collection when the app is not visible.
        val peer = authorizedPeer(context, requireBackgroundLocation = false) ?: return false
        return peer.sendMessage(
            "location.sync.result",
            SecureLocationSync.buildLatestResponse(context, "reconnect-${System.currentTimeMillis()}")
        )
    }
}

/** Schedules the Android-managed 15-minute cadence; execution may be delayed by Doze. */
object LocationCaptureScheduler {
    private const val UNIQUE_PERIODIC = "rkremote-location-periodic"

    fun start(context: Context) {
        LocationHistoryStore(context).setTrackingEnabled(true)
        val request = PeriodicWorkRequestBuilder<LocationCaptureWorker>(15, TimeUnit.MINUTES)
            .addTag(UNIQUE_PERIODIC)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        enqueueImmediate(context)
    }

    fun stop(context: Context) {
        LocationHistoryStore(context).setTrackingEnabled(false)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
    }

    fun enqueueImmediate(context: Context) {
        if (!LocationHistoryStore(context).isTrackingEnabled()) return
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<LocationCaptureWorker>().build())
    }
}

class LocationCaptureWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = LocationHistoryStore(applicationContext)
        if (!store.isTrackingEnabled()) return Result.success()
        val hasFine = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // Keep the periodic schedule alive after a temporary permission revocation.
        // A later user re-grant can resume capture without rebuilding the WorkManager job.
        if (!hasFine && !hasCoarse) return Result.success()

        // Periodic background collection requires the separate Android background grant.
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val manager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = runCatching {
            if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        val previous = store.latestValidFix()
        val fresh = if (enabled) obtainCurrentLocation(manager) else null
        val snapshot = if (fresh != null) {
            LocationSnapshot(
                recordedAtMillis = System.currentTimeMillis(),
                locationEnabled = true,
                latitude = fresh.latitude,
                longitude = fresh.longitude,
                accuracyMeters = if (fresh.hasAccuracy()) fresh.accuracy else null,
                fixTimeMillis = fresh.time,
                freshFix = true
            )
        } else {
            LocationSnapshot(
                recordedAtMillis = System.currentTimeMillis(),
                locationEnabled = enabled,
                latitude = previous?.latitude,
                longitude = previous?.longitude,
                accuracyMeters = previous?.accuracyMeters,
                fixTimeMillis = previous?.fixTimeMillis,
                freshFix = false
            )
        }
        // Location samples are stored locally. The controller receives them only
        // after an authenticated location.sync.request from its paired session.
        store.append(snapshot)
        return Result.success()
    }

    @Suppress("DEPRECATION")
    private suspend fun obtainCurrentLocation(manager: LocationManager): Location? {
        val hasFinePermission = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // Coarse-only grants must not select GPS_PROVIDER: Android requires
        // fine location for GPS and that failure would prevent trying network location.
        val allowedProviders = if (hasFinePermission) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER)
        }
        val provider = allowedProviders.firstOrNull {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        } ?: return null

        return withTimeoutOrNull(20_000L) {
            if (Build.VERSION.SDK_INT >= 30) {
                suspendCancellableCoroutine { continuation ->
                    val cancellation = CancellationSignal()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    runCatching {
                        manager.getCurrentLocation(provider, cancellation, applicationContext.mainExecutor) { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            } else {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (continuation.isActive) continuation.resume(location)
                        }
                        @Deprecated("Deprecated by Android")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    runCatching {
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }
    }
}
