package com.studybook.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

enum class Kind { PDF, DOCX, AUDIO, OTHER }

data class Entry(
    val uri: Uri,
    val docId: String,
    val name: String,
    val mime: String,
    val isDir: Boolean,
) {
    val kind: Kind = kindOf(name, mime)
}

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "3gp", "mka")

fun kindOf(name: String, mime: String): Kind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext == "pdf" || mime == "application/pdf" -> Kind.PDF
        ext == "docx" -> Kind.DOCX
        ext in AUDIO_EXTENSIONS || mime.startsWith("audio/") -> Kind.AUDIO
        else -> Kind.OTHER
    }
}

/** Reads folders through the Storage Access Framework tree the user picked. */
object Docs {
    private val PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
    )

    fun listChildren(context: Context, treeUri: Uri, parentDocId: String): List<Entry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = ArrayList<Entry>()
        context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                // Hidden files, and the ":Zone.Identifier" markers Windows adds to downloaded files.
                if (name.startsWith(".") || name.endsWith(":Zone.Identifier")) continue
                val mime = c.getString(2) ?: ""
                out += Entry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    docId = id,
                    name = name,
                    mime = mime,
                    isDir = mime == Document.MIME_TYPE_DIR,
                )
            }
        }
        return out.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    fun displayName(context: Context, treeUri: Uri, docId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }
}

/** Sorts "Chapter 2" before "Chapter 10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val si = i
                while (i < a.length && a[i].isDigit()) i++
                val sj = j
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
