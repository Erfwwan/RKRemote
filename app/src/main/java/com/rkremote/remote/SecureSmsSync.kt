package com.rkremote.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only, explicitly requested SMS report.
 * Requires Android READ_SMS plus the existing owner-approved secure session gate.
 * Does not collect in the background or persist/export a local copy.
 */
object SecureSmsSync {
    const val MAX_REMOTE_RECORDS = 25
    const val MAX_LOCAL_RECORDS = 100
    private const val MAX_ADDRESS_CHARS = 80
    private const val MAX_BODY_CHARS = 300

    data class ReadResult(val records: JSONArray, val totalAvailable: Int)

    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun resolveContactName(context: Context, address: String): String {
        if (address.isBlank() || ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) return ""
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        return runCatching {
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("").take(120)
    }

    fun readLatest(context: Context, limit: Int = MAX_LOCAL_RECORDS): ReadResult {
        check(hasSmsPermission(context)) {
            "Allow the Android SMS permission on this device first"
        }
        require(limit in 1..MAX_LOCAL_RECORDS)

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        val records = JSONArray()
        var total = 0
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext()) {
                total++
                if (records.length() >= limit) continue
                val address = cursor.getString(addressIndex).orEmpty().take(MAX_ADDRESS_CHARS)
                records.put(
                    JSONObject()
                        .put("address", address)
                        .put("name", resolveContactName(context, address))
                        .put("body", cursor.getString(bodyIndex).orEmpty().take(MAX_BODY_CHARS))
                        .put("timestamp", cursor.getLong(dateIndex).coerceAtLeast(0L))
                        .put("type", cursor.getInt(typeIndex))
                )
            }
        } ?: error("Android SMS provider is unavailable")
        return ReadResult(records, total)
    }

    fun buildLatestResponse(context: Context, requestId: String): JSONObject {
        require(requestId.isNotBlank() && requestId.length <= 128)
        val result = readLatest(context, MAX_REMOTE_RECORDS)
        val response = JSONObject()
            .put("requestId", requestId)
            .put("records", result.records)
            .put("returned", result.records.length())
            .put("available", result.totalAvailable)
            .put("truncated", result.totalAvailable > result.records.length())
        check(response.toString().toByteArray(Charsets.UTF_8).size <= 48 * 1024) {
            "SMS report exceeds the secure message size limit"
        }
        return response
    }
}
