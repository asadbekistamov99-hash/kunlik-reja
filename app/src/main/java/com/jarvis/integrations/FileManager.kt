package com.jarvis.integrations

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.util.Locale

data class FileHit(val name: String, val uri: Uri, val mimeType: String, val sizeBytes: Long, val modifiedMillis: Long)

/**
 * Finds documents by name/extension. Android 11+ scoped storage hides other apps' documents from
 * MediaStore, so PDFs/Office files are searched inside folders the user granted once via the
 * system folder picker (persisted SAF tree permissions). Media files are found through MediaStore.
 */
class FileManager(
    private val context: Context,
    private val launcher: ActivityLauncher,
    private val treeUris: () -> List<String>
) {

    fun search(query: String, extension: String?, limit: Int = 20): List<FileHit> {
        val q = query.lowercase(Locale.ROOT).trim()
        val exts = extension?.takeIf { it.isNotBlank() }?.let { EXT_GROUPS[it] ?: listOf(it) }
        val hits = LinkedHashMap<String, FileHit>()
        fun matches(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            val extOk = exts == null || exts.any { lower.endsWith(".$it") }
            val nameOk = q.isBlank() || q.split(' ').filter { it.length >= 2 }.all { lower.contains(it) }
            return extOk && nameOk
        }
        for (tree in treeUris()) {
            runCatching { walkTree(Uri.parse(tree), ::matches, hits, limit) }
            if (hits.size >= limit) break
        }
        if (hits.size < limit) runCatching { queryMediaStore(::matches, hits, limit) }
        return hits.values.sortedByDescending { it.modifiedMillis }.take(limit)
    }

    fun open(hit: FileHit): ActivityLauncher.Result {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(hit.uri, hit.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launcher.launch(Intent.createChooser(intent, hit.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), hit.name)
    }

    private fun walkTree(tree: Uri, matches: (String) -> Boolean, out: MutableMap<String, FileHit>, limit: Int) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val queue = ArrayDeque<Pair<String, Int>>().apply { add(rootId to 0) }
        var visited = 0
        while (queue.isNotEmpty() && out.size < limit && visited < MAX_DIRS) {
            val (docId, depth) = queue.removeFirst()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(children, DOC_PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) queue.add(id to depth + 1)
                    } else if (matches(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out[uri.toString()] = FileHit(name, uri, mime.ifBlank { mimeOf(name) }, c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
    }

    private fun queryMediaStore(matches: (String) -> Boolean, out: MutableMap<String, FileHit>, limit: Int) {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        context.contentResolver.query(collection, projection, null, null,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val name = c.getString(1) ?: continue
                if (!matches(name)) continue
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                out[uri.toString()] = FileHit(name, uri, c.getString(2) ?: mimeOf(name), c.getLong(3), c.getLong(4) * 1000)
            }
        }
    }

    companion object {
        private const val MAX_DEPTH = 6
        private const val MAX_DIRS = 400
        private val DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val EXT_GROUPS = mapOf(
            "pdf" to listOf("pdf"),
            "doc" to listOf("doc", "docx", "odt", "rtf"),
            "xls" to listOf("xls", "xlsx", "ods", "csv"),
            "ppt" to listOf("ppt", "pptx", "odp"),
            "txt" to listOf("txt", "md")
        )

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
    }
}
