package com.rkremote.remote

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * File operations are restricted to a folder explicitly selected by the device
 * owner through ACTION_OPEN_DOCUMENT_TREE. No filesystem-path or private-app
 * storage access is attempted.
 */
class SafDocumentRepository(context: Context) {\n    private val resolver: ContentResolver = context.contentResolver\n    private val appContext: Context = context.applicationContext
    data class Entry(
        val uri: Uri,
        val name: String,
        val mimeType: String?,
        val sizeBytes: Long?,
        val modifiedAtMillis: Long,
        val isDirectory: Boolean,
        val canRead: Boolean,
        val canWrite: Boolean
    )

    fun listChildren(treeUri: Uri): List<Entry> {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("Selected folder is unavailable")
        if (!tree.canRead()) throw SecurityException("Folder read permission is not available")
        return tree.listFiles().map { file ->
            Entry(
                uri = file.uri,
                name = file.name ?: "unnamed",
                mimeType = file.type,
                sizeBytes = if (file.isFile) file.length().takeIf { it >= 0 } else null,
                modifiedAtMillis = file.lastModified(),
                isDirectory = file.isDirectory,
                canRead = file.canRead(),
                canWrite = file.canWrite()
            )
        }.sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun readFile(uri: Uri, maxBytes: Int = DEFAULT_MAX_READ_BYTES): ByteArray {
        require(maxBytes in 1..MAX_ALLOWED_READ_BYTES)
        val descriptor = DocumentFile.fromSingleUri(resolver.contextOrNull(), uri)
            ?: throw IOException("Document is unavailable")
        if (!descriptor.isFile || !descriptor.canRead()) {
            throw SecurityException("Document is not a readable file")
        }
        if (descriptor.length() > maxBytes) throw IOException("File exceeds the read limit")
        val output = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IOException("File grew beyond the read limit")
                output.write(buffer, 0, count)
            }
        } ?: throw IOException("Unable to open document")
        return output.toByteArray()
    }

    fun delete(uri: Uri): Boolean {
        val document = DocumentFile.fromSingleUri(resolver.contextOrNull(), uri)
            ?: return false
        if (!document.canWrite()) throw SecurityException("Document is not writable")
        return document.delete()
    }

    companion object {
        const val DEFAULT_MAX_READ_BYTES = 2 * 1024 * 1024
        const val MAX_ALLOWED_READ_BYTES = 16 * 1024 * 1024
    }
}

/*
 * DocumentFile requires a Context; ContentResolver does not expose one.
 * The repository therefore uses an explicit Context in its public constructor.
 */
