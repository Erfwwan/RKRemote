package com.rkremote.remote

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Receives ordered secure P2P download messages and publishes verified files locally.
 *
 * An interrupted transfer is kept in the app-private files directory. Its metadata
 * and byte length survive activity recreation and process death. A resumed begin()
 * re-hashes the existing prefix before accepting more bytes; the final SHA-256
 * always covers the complete file.
 */
class SecureDownloadReceiver(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val partsDirectory = File(app.filesDir, PARTS_DIRECTORY).apply {
        if (!exists() && !mkdirs()) throw java.io.IOException("Unable to create transfer staging directory")
    }

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
        val id = payload.getString("transferId")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val size = payload.getLong("totalBytes")
        require(size in 0L..SecureSafTransferBridge.MAX_FILE_BYTES)
        val name = sanitizeName(payload.optString("name", "download.bin"))
        val mime = payload.optString("mimeType", "application/octet-stream")
            .takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) }
            ?: "application/octet-stream"

        // Reuse only an exact matching transfer. A mismatched ID/metadata starts clean.
        val existing = readPersistedMetadata()
        val candidate = partFile(id)
        val canResume = existing?.first == id &&
            existing.second == size &&
            existing.third == name &&
            existing.fourth == mime &&
            candidate.isFile &&
            candidate.length() in 0L..size

        closeOutput()
        if (!canResume) {
            clearPersistedPart(existing?.first)
            candidate.delete()
            transferId = id
            expectedBytes = size
            displayName = name
            mimeType = mime
            tempFile = candidate
            nextOffset = 0L
            digest.reset()
            persistMetadata()
        } else {
            transferId = id
            expectedBytes = size
            displayName = name
            mimeType = mime
            tempFile = candidate
            nextOffset = candidate.length()
            digest.reset()
            candidate.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        output = FileOutputStream(candidate, true)
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
        // The part file length is the authoritative checkpoint; metadata is committed
        // after the bytes have been written so a crash can never advance the offset.
        output?.flush()
    }

    @Synchronized
    fun complete(payload: org.json.JSONObject): String {
        requireActive(payload)
        require(nextOffset == expectedBytes) { "Download is incomplete" }
        output?.flush()
        output?.fd?.sync()
        closeOutput()
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualHash.equals(payload.getString("sha256"), ignoreCase = true)) {
            "Downloaded file SHA-256 does not match"
        }
        val temp = tempFile ?: error("Download temporary file is missing")
        val saved = publish(temp, displayName, mimeType)
        clearState(deleteTemp = true)
        clearPersistedPart(transferId)
        return saved
    }

    @Synchronized
    fun fail(payload: org.json.JSONObject) {
        if (transferId == payload.optString("transferId")) abort()
    }

    /** Close the stream but retain the verified byte prefix for a later resume. */
    @Synchronized
    fun pause() {
        closeOutput()
        transferId = null
        expectedBytes = -1L
        nextOffset = 0L
        displayName = "download.bin"
        mimeType = "application/octet-stream"
        tempFile = null
        digest.reset()
    }

    @Synchronized
    fun hasPendingTransfer(): Boolean = readPersistedMetadata()?.let { metadata ->
        val file = partFile(metadata.first)
        file.isFile && file.length() in 0L..metadata.second
    } == true

    @Synchronized
    fun pendingTransferId(): String? = readPersistedMetadata()?.first
        ?.takeIf { partFile(it).isFile }

    @Synchronized
    fun receivedBytes(): Long = if (transferId != null) nextOffset else pendingOffset()

    @Synchronized
    fun totalBytes(): Long = if (transferId != null) expectedBytes else pendingMetadata()?.totalBytes ?: 0L

    @Synchronized
    fun progressPercent(): Int {
        val total = totalBytes()
        val received = receivedBytes()
        if (total <= 0L) return if (transferId != null && total == 0L) 100 else 0
        return ((received.coerceIn(0L, total) * 100L) / total).toInt()
    }

    @Synchronized
    fun pendingOffset(): Long {
        val metadata = readPersistedMetadata() ?: return 0L
        val file = partFile(metadata.first)
        return if (file.isFile && file.length() in 0L..metadata.second) file.length() else 0L
    }

    @Synchronized
    fun pendingMetadata(): PendingMetadata? {
        val metadata = readPersistedMetadata() ?: return null
        val file = partFile(metadata.first)
        if (!file.isFile || file.length() !in 0L..metadata.second) return null
        return PendingMetadata(metadata.first, metadata.second, metadata.third, metadata.fourth, file.length())
    }

    data class PendingMetadata(
        val transferId: String,
        val totalBytes: Long,
        val name: String,
        val mimeType: String,
        val offset: Long
    )

    @Synchronized
    fun abort() {
        val id = transferId ?: readPersistedMetadata()?.first
        clearState(deleteTemp = true)
        clearPersistedPart(id)
    }

    private fun requireActive(payload: org.json.JSONObject) {
        check(transferId != null && transferId == payload.optString("transferId")) {
            "Unexpected file-transfer identifier"
        }
    }

    private fun partFile(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return File(partsDirectory, "rkremote-$id.part")
    }

    private fun persistMetadata() {
        val id = transferId ?: return
        prefs.edit()
            .putString(KEY_ID, id)
            .putLong(KEY_SIZE, expectedBytes)
            .putString(KEY_NAME, displayName)
            .putString(KEY_MIME, mimeType)
            .commit()
    }

    private fun readPersistedMetadata(): Quadruple? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
        val size = prefs.getLong(KEY_SIZE, -1L)
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val mime = prefs.getString(KEY_MIME, null) ?: return null
        if (size !in 0L..SecureSafTransferBridge.MAX_FILE_BYTES) return null
        return Quadruple(id, size, name, mime)
    }

    private data class Quadruple(val first: String, val second: Long, val third: String, val fourth: String)

    private fun clearPersistedPart(expectedId: String?) {
        val storedId = prefs.getString(KEY_ID, null)
        if (expectedId == null || storedId == expectedId) {
            prefs.edit().clear().commit()
            storedId?.let { runCatching { partFile(it).delete() } }
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
        val directory = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
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

    private fun closeOutput() {
        runCatching { output?.flush() }
        runCatching { output?.fd?.sync() }
        runCatching { output?.close() }
        output = null
    }

    private fun clearState(deleteTemp: Boolean) {
        closeOutput()
        if (deleteTemp) runCatching { tempFile?.delete() }
        tempFile = null
        transferId = null
        expectedBytes = -1L
        nextOffset = 0L
        displayName = "download.bin"
        mimeType = "application/octet-stream"
        digest.reset()
    }

    override fun close() = pause()

    companion object {
        private const val PREFS_NAME = "rkremote_resumable_download"
        private const val PARTS_DIRECTORY = "rkremote-transfer-parts"
        private const val KEY_ID = "transfer_id"
        private const val KEY_SIZE = "total_bytes"
        private const val KEY_NAME = "display_name"
        private const val KEY_MIME = "mime_type"
    }
}
