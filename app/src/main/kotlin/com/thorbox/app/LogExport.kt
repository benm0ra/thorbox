// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a text file into the shared Downloads folder (MediaStore; no storage permission on Android 10+). */
object LogExport {
    /** Returns the file name on success, null on failure. Call off the main thread. */
    fun saveToDownloads(context: Context, prefix: String, text: String): String? =
        saveBytes(context, prefix, "txt", "text/plain", text.toByteArray())

    fun saveBytes(context: Context, prefix: String, ext: String, mime: String, bytes: ByteArray): String? = runCatching {
        val name = "$prefix-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".$ext"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            r.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            r.update(uri, values, null, null)
        }.isSuccess
        if (!ok) { runCatching { r.delete(uri, null, null) }; return null }
        name
    }.getOrNull()
}
