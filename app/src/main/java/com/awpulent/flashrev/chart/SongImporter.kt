package com.awpulent.flashrev.chart

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Copies StepMania songs from a user-selected document tree or .zip file into the
 * app's private songs directory. Only step files and audio are copied; a song is kept
 * only if its step file contains at least one dance-single chart.
 */
class SongImporter(private val context: Context, private val songsDir: File) {

    class Result(val imported: Int, val skipped: Int)

    private val resolver: ContentResolver get() = context.contentResolver

    /** Imports from a tree URI returned by ACTION_OPEN_DOCUMENT_TREE. */
    fun importTree(treeUri: Uri): Result {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryDisplayName(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)) ?: "Imported"
        var imported = 0
        var skipped = 0
        fun visit(docId: String, name: String, depth: Int) {
            val children = listChildren(treeUri, docId)
            val hasStepFile = children.any { !it.isDir && extensionOf(it.name) in SongLibrary.STEP_EXTENSIONS }
            if (hasStepFile) {
                val ok = importSongDocuments(name, children.filter { !it.isDir }) { child ->
                    resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId))
                }
                if (ok) imported++ else skipped++
                return
            }
            if (depth >= MAX_DEPTH) return
            for (c in children) if (c.isDir) visit(c.docId, c.name, depth + 1)
        }
        visit(rootId, rootName, 0)
        return Result(imported, skipped)
    }

    /** Imports from a .zip pack picked with ACTION_OPEN_DOCUMENT. */
    fun importZip(uri: Uri): Result {
        val temp = File(songsDir, ".import_tmp").also { it.deleteRecursively(); it.mkdirs() }
        try {
            resolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.replace('\\', '/').trimStart('/')
                        if (name.split('/').any { it == ".." }) continue // zip-slip guard
                        if (extensionOf(name) !in ALLOWED_EXTENSIONS) continue
                        val out = File(temp, name)
                        if (!out.canonicalPath.startsWith(temp.canonicalPath)) continue
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            } ?: return Result(0, 0)
            var imported = 0
            var skipped = 0
            fun visit(dir: File, depth: Int) {
                val files = dir.listFiles() ?: return
                if (files.any { it.isFile && it.extension.lowercase() in SongLibrary.STEP_EXTENSIONS }) {
                    val target = uniqueSongDir(dir.name.ifBlank { "song" })
                    if (target.exists()) target.deleteRecursively()
                    if (dir.renameTo(target) || copyDir(dir, target)) {
                        if (SongLibrary.loadSong(target) != null) imported++ else { target.deleteRecursively(); skipped++ }
                    } else skipped++
                    return
                }
                if (depth >= MAX_DEPTH) return
                for (f in files) if (f.isDirectory) visit(f, depth + 1)
            }
            visit(temp, 0)
            return Result(imported, skipped)
        } finally {
            temp.deleteRecursively()
        }
    }

    // ----------------------------------------------------------------- helpers

    private class Child(val docId: String, val name: String, val isDir: Boolean)

    private fun listChildren(treeUri: Uri, parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = ArrayList<Child>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                result.add(Child(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR))
            }
        }
        return result
    }

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun importSongDocuments(name: String, files: List<Child>, open: (Child) -> InputStream?): Boolean {
        val target = uniqueSongDir(name)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        for (f in files) {
            if (extensionOf(f.name) !in ALLOWED_EXTENSIONS) continue
            val safeName = File(f.name).name
            val input = open(f) ?: continue
            input.use { inp -> File(target, safeName).outputStream().use { inp.copyTo(it) } }
        }
        return if (SongLibrary.loadSong(target) != null) true else { target.deleteRecursively(); false }
    }

    private fun uniqueSongDir(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9 ._()\\[\\]-]"), "_").trim().ifBlank { "song" }.take(80)
        return File(songsDir, safe.let { if (it.startsWith("_")) "s$it" else it })
    }

    private fun copyDir(src: File, dst: File): Boolean = runCatching {
        src.copyRecursively(dst, overwrite = true)
        src.deleteRecursively()
    }.getOrDefault(false)

    private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

    companion object {
        private const val MAX_DEPTH = 3
        private val ALLOWED_EXTENSIONS = SongLibrary.STEP_EXTENSIONS + SongLibrary.AUDIO_EXTENSIONS
    }
}
