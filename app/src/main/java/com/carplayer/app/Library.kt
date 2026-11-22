package com.carplayer.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.File

data class Track(
    val path: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /**
     * URI `content://` di MediaStore. È questo che si usa per leggere il file:
     * sulle chiavette USB Android nega l'accesso al percorso (`EACCES`), mentre
     * il provider della musica lo apre senza problemi.
     */
    val uri: String = ""
) {
    /** Sorgente da dare al motore: l'URI se c'è, altrimenti il percorso. */
    val playUri: String get() = uri.ifBlank { path }
    val folder: String get() = File(path).parent ?: ""
}

object Library {

    /** Legge tutti i brani indicizzati da Android (MediaStore). */
    fun scan(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val path = c.getString(1) ?: continue
                    val title = c.getString(2)?.takeIf { it.isNotBlank() }
                        ?: File(path).nameWithoutExtension
                    val artist = c.getString(3)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: ""
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    ).toString()
                    out.add(Track(path, title, artist, c.getLong(4), uri))
                }
            }
        } catch (e: Exception) {
            // permesso negato o provider non disponibile: lista vuota
        }
        out.sortBy { it.path.lowercase() }
        return out
    }

    fun folders(tracks: List<Track>): List<Pair<String, Int>> =
        tracks.groupBy { it.folder }
            .map { it.key to it.value.size }
            .sortedBy { it.first.lowercase() }

    /** "Genitore/Nome" per riconoscere cartelle con lo stesso nome. */
    fun label(folderPath: String): String {
        val f = File(folderPath)
        val parent = f.parentFile?.name
        return if (parent.isNullOrEmpty()) f.name else "$parent/${f.name}"
    }
}

/** Copertine: prima quella incorporata nel file (via URI MediaStore), poi cover/folder.jpg nella cartella. */
object Covers {
    private val names = setOf("cover", "folder", "front", "album", "albumart", "albumartsmall")
    private val exts = setOf("jpg", "jpeg", "png")

    fun load(ctx: Context, uri: String, path: String, size: Int): Bitmap? =
        embedded(ctx, uri, size) ?: fromFolder(File(path).parentFile, size)

    private fun embedded(ctx: Context, uri: String, size: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            // il percorso su USB darebbe EACCES: meglio l'URI del provider
            if (uri.startsWith("content://")) r.setDataSource(ctx, Uri.parse(uri))
            else r.setDataSource(uri)
            val bytes = r.embeddedPicture
            if (bytes == null) null else decode(bytes, size)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    private fun fromFolder(dir: File?, size: Int): Bitmap? {
        val files = dir?.listFiles() ?: return null
        val f = files.firstOrNull {
            it.extension.lowercase() in exts && it.nameWithoutExtension.lowercase() in names
        } ?: return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, size) }
            BitmapFactory.decodeFile(f.path, o2)
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(b: ByteArray, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, size) }
        return BitmapFactory.decodeByteArray(b, 0, b.size, o2)
    }

    private fun sample(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        return s
    }
}
