package com.musiccut.app.data

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract

data class BrowserItem(val name: String, val uri: Uri, val mime: String, val size: Long, val isDir: Boolean)

/**
 * Loop / media browser over a user-chosen folder (Storage Access Framework tree), with preview
 * playback through MediaPlayer.
 */
class MediaBrowser(private val context: Context) {
    private var player: MediaPlayer? = null

    fun persist(tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Lists audio files and sub-folders of [docId] inside [tree] (root when [docId] is null). */
    fun list(tree: Uri, docId: String? = null): List<BrowserItem> {
        val parent = docId ?: DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val out = ArrayList<BrowserItem>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (!isDir && !mime.startsWith("audio/") && !name.lowercase().let { n -> AUDIO_EXT.any { n.endsWith(it) } }) continue
                out += BrowserItem(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), mime, size, isDir)
            }
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun docId(uri: Uri): String = DocumentsContract.getDocumentId(uri)

    fun preview(uri: Uri, onDone: () -> Unit = {}) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(context, uri)
            setOnCompletionListener { onDone() }
            prepare()
            start()
        }
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
    }

    companion object {
        val AUDIO_EXT = listOf(".wav", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".aif", ".aiff", ".caf")
    }
}
