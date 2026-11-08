package com.rkremote.remote

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.security.MessageDigest

/**
 * Secure-channel SAF bridge. Every document ID is checked against the exact
 * tree URI the device owner selected in RKRemote setup. This class never uses
 * raw filesystem paths and never lists shared storage outside that tree.
 */
class SecureSafTransferBridge(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    fun list(parentId: String?, limit: Int = MAX_LIST_ITEMS): JSONObject {
        require(limit in 1..MAX_LIST_ITEMS)
        val tree = selectedTree()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val parent = parentId?.takeIf { it.isNotBlank() } ?: rootId
        requireInsideTree(tree, parent)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
        val parentMime = queryString(parentUri, DocumentsContract.Document.COLUMN_MIME_TYPE)
        require(parentMime == DocumentsContract.Document.MIME_TYPE_DIR) { "Selected item is not a folder" }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val items = JSONArray()
        var truncated = false
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (items.length() >= limit) {
                    truncated = true
                    break
                }
                val id = cursor.getString(0)?.takeIf { it.isNotBlank() && it.length <= 512 } ?: continue
                val mime = cursor.getString(2) ?: "application/octet-stream"
                items.put(JSONObject()
                    .put("id", id)
                    .put("name", (cursor.getString(1) ?: "unnamed").take(255))
                    .put("directory", mime == DocumentsContract.Document.MIME_TYPE_DIR)
                    .put("size", if (cursor.isNull(3)) JSONObject.NULL else cursor.getLong(3).coerceAtLeast(0L))
                    .put("modifiedAt", if (cursor.isNull(4)) 0L else cursor.getLong(4).coerceAtLeast(0L))
                    .put("mimeType", mime.take(160)))
            }
        } ?: throw IOException("Unable to list the owner-selected folder")
        return JSONObject()
            .put("parentId", parent)
            .put("rootId", rootId)
            .put("items", items)
            .put("truncated", truncated)
    }

    /**
     * Reads a single bounded chunk. Reopening the SAF stream per request keeps
     * transfer state stateless across reconnects; the controller advances offset
     * only after receiving and validating each chunk.
     */
    fun readChunk(documentId: String, offset: Long, requestedBytes: Int): JSONObject {
        require(offset >= 0L)
        require(requestedBytes in 1..MAX_CHUNK_BYTES)
        val tree = selectedTree()
        requireInsideTree(tree, documentId)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val metadata = resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(
                cursor.getString(0) ?: "download.bin",
                cursor.getString(1) ?: "application/octet-stream",
                if (cursor.isNull(2)) -1L else cursor.getLong(2)
            )
        } ?: throw IOException("Document is unavailable")
        require(metadata.second != DocumentsContract.Document.MIME_TYPE_DIR) { "Folders cannot be downloaded" }
        require(metadata.third in 0L..MAX_FILE_BYTES) { "File is larger than the secure transfer limit" }
        require(offset <= metadata.third) { "Download offset is outside the file" }

        val bytes = resolver.openInputStream(uri)?.use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val count = input.skip(offset - skipped)
                if (count <= 0L) {
                    if (input.read() < 0) throw IOException("Unable to seek to requested file offset")
                    skipped++
                } else skipped += count
            }
            val wanted = minOf(requestedBytes.toLong(), metadata.third - offset).toInt()
            val buffer = ByteArray(wanted)
            var read = 0
            while (read < wanted) {
                val count = input.read(buffer, read, wanted - read)
                if (count < 0) break
                read += count
            }
            if (read == wanted) buffer else buffer.copyOf(read)
        } ?: throw IOException("Unable to open document")
        val nextOffset = offset + bytes.size
        return JSONObject()
            .put("documentId", documentId)
            .put("name", metadata.first.take(255))
            .put("mimeType", metadata.second.take(160))
            .put("totalBytes", metadata.third)
            .put("offset", offset)
            .put("nextOffset", nextOffset)
            .put("eof", nextOffset >= metadata.third)
            .put("data", Base64.getEncoder().encodeToString(bytes))
    }


    data class FileMetadata(val documentId: String, val name: String, val mimeType: String, val sizeBytes: Long)

    /** Streams one owner-selected file sequentially; callback false aborts immediately. */
    fun streamFile(
        documentId: String,
        onStart: (FileMetadata) -> Boolean,
        onChunk: (Long, ByteArray) -> Boolean,
        onComplete: (String) -> Unit
    ) {
        val tree = selectedTree()
        requireInsideTree(tree, documentId)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val metadata = resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else FileMetadata(
                documentId,
                cursor.getString(0) ?: "download.bin",
                cursor.getString(1) ?: "application/octet-stream",
                if (cursor.isNull(2)) -1L else cursor.getLong(2)
            )
        } ?: throw IOException("Document is unavailable")
        require(metadata.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) { "Folders cannot be downloaded" }
        require(metadata.sizeBytes in 0L..MAX_FILE_BYTES) { "File is larger than the secure transfer limit" }
        check(onStart(metadata)) { "Controller rejected the file transfer" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAX_CHUNK_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count.toLong()
                require(total <= MAX_FILE_BYTES) { "File grew beyond the secure transfer limit" }
                digest.update(buffer, 0, count)
                if (!onChunk(total - count, buffer.copyOf(count))) {
                    throw IOException("Controller closed the file transfer")
                }
            }
        } ?: throw IOException("Unable to open document")
        if (metadata.sizeBytes >= 0L && total != metadata.sizeBytes) {
            throw IOException("File size changed during transfer")
        }
        onComplete(digest.digest().joinToString("") { "%02x".format(it) })
    }


    private data class PendingUpload(
        val transferId: String,
        val documentUri: Uri,
        val output: OutputStream,
        val expectedBytes: Long,
        val digest: MessageDigest,
        var nextOffset: Long = 0L
    )
    private var pendingUpload: PendingUpload? = null

    @Synchronized
    fun beginUpload(
        transferId: String,
        rawName: String,
        rawMimeType: String,
        totalBytes: Long,
        parentId: String?
    ) {
        require(transferId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(totalBytes in 0L..MAX_FILE_BYTES) { "Upload exceeds the 50 MiB limit" }
        check(pendingUpload == null) { "Another upload is already active" }
        val name = sanitizeUploadName(rawName)
        val mime = rawMimeType.takeIf {
            it.count { ch -> ch == '/' } == 1 && it.all { ch -> ch.isLetterOrDigit() || ch in "!#&^_.+-/" }
        } ?: "application/octet-stream"
        val tree = selectedTree()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val parent = parentId?.takeIf { it.isNotBlank() } ?: rootId
        requireInsideTree(tree, parent)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
        require(queryString(parentUri, DocumentsContract.Document.COLUMN_MIME_TYPE) ==
            DocumentsContract.Document.MIME_TYPE_DIR) { "Upload destination is not a folder" }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val duplicate = resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == name) {
                    found = true
                    break
                }
            }
            found
        } ?: false
        require(!duplicate) { "A file with this name already exists; overwrite is disabled" }
        val created = DocumentsContract.createDocument(resolver, parentUri, mime, name)
            ?: throw IOException("Unable to create file in the selected folder")
        try {
            val output = resolver.openOutputStream(created, "w")
                ?: throw IOException("Unable to open destination file")
            pendingUpload = PendingUpload(
                transferId,
                created,
                output,
                totalBytes,
                MessageDigest.getInstance("SHA-256")
            )
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }
    }

    @Synchronized
    fun appendUploadChunk(transferId: String, offset: Long, bytes: ByteArray) {
        val pending = pendingUpload ?: throw IllegalStateException("No upload is active")
        require(pending.transferId == transferId) { "Unexpected upload identifier" }
        require(offset == pending.nextOffset) { "Upload chunk is missing, duplicated or out of order" }
        require(bytes.isNotEmpty() && bytes.size <= MAX_CHUNK_BYTES)
        require(pending.nextOffset + bytes.size <= pending.expectedBytes) { "Upload exceeds announced size" }
        pending.output.write(bytes)
        pending.digest.update(bytes)
        pending.nextOffset += bytes.size
    }

    @Synchronized
    fun completeUpload(transferId: String, expectedSha256: String) {
        val pending = pendingUpload ?: throw IllegalStateException("No upload is active")
        require(pending.transferId == transferId) { "Unexpected upload identifier" }
        try {
            require(pending.nextOffset == pending.expectedBytes) { "Upload is incomplete" }
            pending.output.flush()
            pending.output.close()
            val actual = pending.digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expectedSha256, ignoreCase = true)) { "Upload SHA-256 does not match" }
            pendingUpload = null
        } catch (error: Exception) {
            runCatching { pending.output.close() }
            runCatching { DocumentsContract.deleteDocument(resolver, pending.documentUri) }
            pendingUpload = null
            throw error
        }
    }

    @Synchronized
    fun abortUpload() {
        val pending = pendingUpload ?: return
        pendingUpload = null
        runCatching { pending.output.close() }
        runCatching { DocumentsContract.deleteDocument(resolver, pending.documentUri) }
    }

    private fun sanitizeUploadName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 32 && it.code != 127 }
            .take(180)
            .trim()
        require(name.isNotBlank() && name != "." && name != "..")
        return name
    }

    private fun selectedTree(): Uri {
        val raw = app.getSharedPreferences("rkremote_setup", Context.MODE_PRIVATE)
            .getString("selected_tree_uri", null)
            ?: throw SecurityException("No folder has been selected by the device owner")
        val uri = Uri.parse(raw)
        val tree = DocumentFile.fromTreeUri(app, uri)
            ?: throw IOException("Owner-selected folder is unavailable")
        if (!tree.canRead()) throw SecurityException("Owner-selected folder is not readable")
        return uri
    }

    private fun requireInsideTree(tree: Uri, documentId: String) {
        require(documentId.isNotBlank() && documentId.length <= MAX_DOCUMENT_ID_CHARS)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val inside = documentId == rootId || runCatching {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val child = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            DocumentsContract.isChildDocument(resolver, root, child)
        }.getOrDefault(false)
        if (!inside) throw SecurityException("Document is outside the owner-selected folder")
    }

    private fun queryString(uri: Uri, column: String): String? =
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    companion object {
        const val MAX_LIST_ITEMS = 40
        const val MAX_CHUNK_BYTES = 24 * 1024
        const val MAX_FILE_BYTES = 50L * 1024L * 1024L
        private const val MAX_DOCUMENT_ID_CHARS = 2048
    }
}
