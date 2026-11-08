package com.rkremote.remote

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

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
                val id = cursor.getString(0) ?: continue
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
        const val MAX_LIST_ITEMS = 100
        const val MAX_CHUNK_BYTES = 24 * 1024
        const val MAX_FILE_BYTES = 50L * 1024L * 1024L
        private const val MAX_DOCUMENT_ID_CHARS = 2048
    }
}
