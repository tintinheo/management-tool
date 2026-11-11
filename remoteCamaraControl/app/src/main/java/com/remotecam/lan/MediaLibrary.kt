package com.remotecam.lan

import java.io.InputStream

data class MediaItem(
    val id: Long,
    val name: String,
    val mime: String,
    val size: Long,
    val dateMs: Long,
    val video: Boolean,
)

class MediaFile(val name: String, val mime: String, val size: Long, val input: InputStream)

/** Photos and videos this app saved, for the browser gallery. */
interface MediaLibrary {
    fun list(limit: Int): List<MediaItem>
    fun thumbnail(id: Long): ByteArray?
    fun open(id: Long): MediaFile?
}
