package org.ecotecworld.sftpclient.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

/** Helpers for reading picked files and saving downloads to the phone's Downloads folder. */
object DeviceFiles {
    const val DOWNLOAD_SUBFOLDER = "SFTP Client"

    fun mimeTypeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Creates a pending entry in Download/SFTP Client. Android renames it if the name is taken. */
    fun createDownload(resolver: ContentResolver, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBFOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    /** Marks a finished download as complete so other apps can see it. */
    fun finishDownload(resolver: ContentResolver, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    data class PickedFile(val uri: Uri, val name: String, val size: Long)

    fun describe(resolver: ContentResolver, uri: Uri): PickedFile {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val safeName = (name ?: uri.lastPathSegment ?: "upload").substringAfterLast('/').ifBlank { "upload" }
        return PickedFile(uri, safeName, size)
    }
}
