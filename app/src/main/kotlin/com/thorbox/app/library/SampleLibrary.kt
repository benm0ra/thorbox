// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class LibraryItem(val uri: Uri, val name: String, val folder: String, val sizeBytes: Long)

/**
 * A "watched" samples folder: you grant one folder once (Storage Access Framework) and it's
 * browsable instantly from then on, with no picker per import. That keeps the flow fast.
 * Uses DocumentsContract queries directly (much faster than DocumentFile for big folders).
 */
class SampleLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val _root = MutableStateFlow(prefs.getString(KEY_ROOT, null)?.let(Uri::parse))
    val root = _root.asStateFlow()
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items = _items.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    fun setRoot(treeUri: Uri) {
        val cr = context.contentResolver
        cr.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Least privilege: drop grants for any previously chosen folder (read-only, one folder at a time).
        cr.persistedUriPermissions.filter { it.uri != treeUri }.forEach {
            runCatching { cr.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().putString(KEY_ROOT, treeUri.toString()).apply()
        _root.value = treeUri
    }

    /** Recursive scan up to [maxDepth] levels. Call from a coroutine. */
    suspend fun rescan(maxDepth: Int = 4) {
        val tree = _root.value ?: return
        _scanning.value = true
        val found = withContext(Dispatchers.IO) {
            val out = ArrayList<LibraryItem>()
            runCatching {
                walk(tree, DocumentsContract.getTreeDocumentId(tree), "", 0, maxDepth, out)
            }
            out.sortedWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() }))
        }
        _items.value = found
        _scanning.value = false
    }

    private fun walk(tree: Uri, docId: String, folder: String, depth: Int, maxDepth: Int, out: MutableList<LibraryItem>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < maxDepth && !name.startsWith(".")) {
                        walk(tree, id, if (folder.isEmpty()) name else "$folder/$name", depth + 1, maxDepth, out)
                    }
                } else if (isAudioName(name)) {
                    out += LibraryItem(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, folder, c.getLong(3))
                }
            }
        }
    }

    companion object {
        private const val KEY_ROOT = "root"
        // The spec asks for wav + mp3. The platform decoder handles the rest for free.
        val AUDIO_EXT = setOf("wav", "mp3", "flac", "ogg", "m4a", "aif", "aiff")

        /**
         * A sample worth listing. Hidden files are skipped, above all macOS's "._name.wav" AppleDouble
         * files: a Mac copies one next to every sample on a FAT/exFAT card or USB stick. They carry
         * the audio's extension but hold only Finder metadata, so they can never load.
         */
        fun isAudioName(name: String): Boolean =
            !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
    }
}
