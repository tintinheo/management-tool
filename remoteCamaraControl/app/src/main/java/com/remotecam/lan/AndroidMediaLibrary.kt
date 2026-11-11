package com.remotecam.lan

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.ByteArrayOutputStream

/**
 * Reads back what RemoteCam saved under Pictures/RemoteCam and Movies/RemoteCam. Files an app created
 * itself are readable without any storage permission.
 */
class AndroidMediaLibrary(private val resolver: ContentResolver) : MediaLibrary {

    /** About 4 MB of thumbnails, so reopening the gallery on a slow PC costs the phone nothing. */
    private val thumbs = object : LruCache<Long, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ByteArray) = value.size
    }

    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override fun list(limit: Int): List<MediaItem> =
        (query(images, "Pictures/RemoteCam%", video = false, limit) + query(videos, "Movies/RemoteCam%", video = true, limit))
            .sortedByDescending { it.dateMs }
            .take(limit)

    override fun thumbnail(id: Long): ByteArray? {
        thumbs.get(id)?.let { return it }
        val item = find(id) ?: return null
        val bitmap = runCatching { resolver.loadThumbnail(uriOf(item), Size(320, 320), null) }.getOrNull() ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        bitmap.recycle()
        return out.toByteArray().also { thumbs.put(id, it) }
    }

    override fun open(id: Long): MediaFile? {
        val item = find(id) ?: return null
        val input = runCatching { resolver.openInputStream(uriOf(item)) }.getOrNull() ?: return null
        return MediaFile(item.name, item.mime, item.size, input)
    }

    private fun uriOf(item: MediaItem): Uri = ContentUris.withAppendedId(if (item.video) videos else images, item.id)

    private fun find(id: Long): MediaItem? =
        queryById(images, id, video = false) ?: queryById(videos, id, video = true)

    private fun query(collection: Uri, pathPattern: String, video: Boolean, limit: Int): List<MediaItem> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(pathPattern))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return read(collection, args, video)
    }

    private fun queryById(collection: Uri, id: Long, video: Boolean): MediaItem? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns._ID} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
        }
        return read(collection, args, video).firstOrNull()
    }

    private fun read(collection: Uri, args: Bundle, video: Boolean): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        return runCatching {
            resolver.query(collection, projection, args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            MediaItem(
                                id = c.getLong(0),
                                name = c.getString(1).orEmpty(),
                                mime = c.getString(2) ?: "application/octet-stream",
                                size = c.getLong(3),
                                dateMs = c.getLong(4) * 1000,
                                video = video,
                            ),
                        )
                    }
                }
            }
        }.getOrNull().orEmpty()
    }
}
