package com.rkremote.remote

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Receives ordered secure P2P download messages and publishes verified files locally. */
class SecureDownloadReceiver(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private var transferId: String? = null
    private var expectedBytes = -1L
    private var nextOffset = 0L
    private var displayName = "download.bin"
    private var mimeType = "application/octet-stream"
    private var tempFile: File? = null
    private var output: FileOutputStream? = null
    private val digest = MessageDigest.getInstance("SHA-256")

    @Synchronized
    fun begin(payload: org.json.JSONObject) {
        abort()
        val id = payload.getString("transferId")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val size = payload.getLong("totalBytes")
        require(size in 0L..SecureSafTransferBridge.MAX_FILE_BYTES)
        val name = sanitizeName(payload.optString("name", "download.bin"))
        val mime = payload.optString("mimeType", "application/octet-stream")
            .takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) }
            ?: "application/octet-stream"
        val temp = File(app.cacheDir, "rkremote-$id.part")
        output = FileOutputStream(temp, false)
        tempFile = temp
        transferId = id
        expectedBytes = size
        nextOffset = 0L
        displayName = name
        mimeType = mime
        digest.reset()
    }

    @Synchronized
    fun chunk(payload: org.json.JSONObject) {
        requireActive(payload)
        val offset = payload.getLong("offset")
        require(offset == nextOffset) { "Download chunk is missing, duplicated or out of order" }
        val encoded = payload.getString("data")
        require(encoded.length <= 4 * 1024 * 1024) { "Download chunk is too large" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.isNotEmpty() && bytes.size <= SecureSafTransferBridge.MAX_CHUNK_BYTES)
        require(nextOffset + bytes.size <= expectedBytes) { "Download exceeds announced file size" }
        output?.write(bytes)
        digest.update(bytes)
        nextOffset += bytes.size
    }

    @Synchronized
    fun complete(payload: org.json.JSONObject): String {
        requireActive(payload)
        require(nextOffset == expectedBytes) { "Download is incomplete" }
        output?.flush()
        output?.fd?.sync()
        output?.close()
        output = null
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualHash.equals(payload.getString("sha256"), ignoreCase = true)) {
            "Downloaded file SHA-256 does not match"
        }
        val temp = tempFile ?: error("Download temporary file is missing")
        val saved = publish(temp, displayName, mimeType)
        clearState(deleteTemp = true)
        return saved
    }

    @Synchronized
    fun fail(payload: org.json.JSONObject) {
        if (transferId == payload.optString("transferId")) abort()
    }

    @Synchronized
    fun abort() = clearState(deleteTemp = true)

    private fun requireActive(payload: org.json.JSONObject) {
        check(transferId != null && transferId == payload.optString("transferId")) {
            "Unexpected file-transfer identifier"
        }
    }

    private fun publish(source: File, name: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RKRemote/Received")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Unable to create destination in Downloads")
            try {
                app.contentResolver.openOutputStream(uri, "w")?.use { target ->
                    source.inputStream().use { it.copyTo(target, 64 * 1024) }
                } ?: throw java.io.IOException("Unable to write destination file")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                app.contentResolver.update(uri, values, null, null)
                return uri.toString()
            } catch (error: Exception) {
                app.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        val directory = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: app.filesDir
        val received = File(directory, "RKRemote/Received")
        if (!received.exists() && !received.mkdirs()) throw java.io.IOException("Unable to create received-files folder")
        var destination = File(received, name)
        if (destination.exists()) {
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").takeIf { it != name }
            var index = 1
            do {
                val candidate = base + " ($index)" + (extension?.let { ".$it" } ?: "")
                destination = File(received, candidate)
                index++
            } while (destination.exists() && index < 10000)
        }
        source.copyTo(destination, overwrite = false)
        return destination.absolutePath
    }

    private fun sanitizeName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 32 && it.code != 127 }
            .take(180)
            .trim()
        require(name.isNotBlank() && name != "." && name != "..")
        return name
    }

    private fun clearState(deleteTemp: Boolean) {
        runCatching { output?.close() }
        output = null
        if (deleteTemp) runCatching { tempFile?.delete() }
        tempFile = null
        transferId = null
        expectedBytes = -1L
        nextOffset = 0L
        digest.reset()
    }

    override fun close() = abort()
}
