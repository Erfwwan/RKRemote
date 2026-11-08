package com.rkremote.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Read-only, explicitly requested address-book export. */
object SecureContactsSync {
    const val MAX_REMOTE_RECORDS = 180
    const val MAX_LOCAL_RECORDS = 300
    private const val MAX_FIELD_CHARS = 120

    data class ReadResult(val records: JSONArray, val totalAvailable: Int)

    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun readLatest(context: Context, limit: Int = MAX_LOCAL_RECORDS): ReadResult {
        check(hasContactsPermission(context)) { "Allow Android Contacts permission on this device first" }
        require(limit in 1..MAX_LOCAL_RECORDS)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val records = JSONArray()
        val seen = HashSet<String>()
        var total = 0
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val contactId = cursor.getString(idIndex).orEmpty()
                val name = cursor.getString(nameIndex).orEmpty().take(MAX_FIELD_CHARS)
                val number = cursor.getString(numberIndex).orEmpty().take(MAX_FIELD_CHARS)
                val key = "$contactId|$number"
                if (number.isBlank() || !seen.add(key)) continue
                total++
                if (records.length() < limit) {
                    records.put(JSONObject().put("id", contactId).put("name", name.ifBlank { "بدون نام" }).put("number", number))
                }
            }
        } ?: error("Android contacts provider is unavailable")
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
            "Contacts response exceeds the secure message size limit"
        }
        return response
    }
}
