package com.rkremote.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only call-log export. Android's READ_CALL_LOG permission and a separate
 * owner-approved CALL_LOG session capability are both required.
 *
 * No background collection, persistent copy, or automatic upload is performed.
 */
object SecureCallLogSync {
    const val MAX_REMOTE_RECORDS = 35
    const val MAX_LOCAL_RECORDS = 100
    private const val MAX_FIELD_CHARS = 180

    data class ReadResult(
        val records: JSONArray,
        val totalAvailable: Int
    )

    fun hasCallLogPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

    fun readLatest(context: Context, limit: Int = MAX_LOCAL_RECORDS): ReadResult {
        check(hasCallLogPermission(context)) {
            "Allow the Android Call history permission on this device first"
        }
        require(limit in 1..MAX_LOCAL_RECORDS)

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val records = JSONArray()
        var total = 0
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext()) {
                total++
                if (records.length() >= limit) continue
                val timestamp = cursor.getLong(dateIndex)
                val duration = cursor.getLong(durationIndex).coerceAtLeast(0L)
                records.put(
                    JSONObject()
                        .put("number", cursor.getString(numberIndex).orEmpty().take(MAX_FIELD_CHARS))
                        .put("name", cursor.getString(nameIndex).orEmpty().take(MAX_FIELD_CHARS))
                        .put("type", cursor.getInt(typeIndex))
                        .put("timestamp", timestamp.coerceAtLeast(0L))
                        .put("durationSeconds", duration)
                )
            }
        } ?: error("Android call history provider is unavailable")
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
            "Call history response exceeds the secure message size limit"
        }
        return response
    }
}
