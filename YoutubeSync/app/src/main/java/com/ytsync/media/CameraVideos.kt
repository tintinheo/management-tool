package com.ytsync.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import com.ytsync.data.UploadItem
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A video in DCIM/Camera as MediaStore sees it. [recordedAt] = when it was filmed (epoch ms). */
data class CameraVideo(
    val mediaId: Long,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val recordedAt: Long,
    val durationMs: Long,
    val addedEpochSec: Long,
    /** App that created the file (MediaStore OWNER_PACKAGE_NAME); null = Android doesn't know. */
    val ownerPackage: String? = null,
    /** Filmed by a camera app — see [CameraVideos.isFromCamera]. Others are never auto-uploaded. */
    val fromCamera: Boolean = true,
) {
    fun toItem(state: com.ytsync.data.UploadState = com.ytsync.data.UploadState.QUEUED) = UploadItem(
        mediaId = mediaId, displayName = displayName, sizeBytes = sizeBytes, mimeType = mimeType,
        takenAt = recordedAt, durationMs = durationMs, state = state,
    )
}

/** Finds videos recorded by the phone's camera app (DCIM/Camera on Xiaomi/HyperOS and stock Android). */
object CameraVideos {

    val collection: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun uriOf(mediaId: Long): Uri = ContentUris.withAppendedId(collection, mediaId)

    /** "DCIM/Camera/" — also accepts sub-folders and a missing trailing slash, never "DCIM/CameraBackup". */
    fun isCameraFolder(relativePath: String?): Boolean {
        val p = relativePath?.replace('\\', '/')?.trimStart('/')?.lowercase() ?: return false
        return p == "dcim/camera" || p.startsWith("dcim/camera/")
    }

    // ---- camera vs. downloaded / edited ---------------------------------------------------------

    /** Camera apps on Xiaomi/HyperOS (+ common others); extended at runtime by [cameraPackages]. */
    val KNOWN_CAMERA_PACKAGES = setOf(
        "com.android.camera",            // Xiaomi / HyperOS camera
        "com.xiaomi.camera",
        "com.google.android.GoogleCamera",
        "com.sec.android.app.camera",
        "org.codeaurora.snapcam",
        "com.android.camera2",
    )

    /** Names camera apps give videos: VID_20261109_175130*, PXL_20261109_175130123*, 20261109_175130*. */
    private val cameraName = Regex("""^(?:VID|PXL|MVIMG)_\d{8}_\d{6,9}(?:[_.].*)?$|^\d{8}_\d{6}(?:[_.(].*)?$""", RegexOption.IGNORE_CASE)

    fun hasCameraName(displayName: String) = cameraName.matches(displayName)

    /**
     * Every installed app that can record video (handles VIDEO_CAPTURE / VIDEO_CAMERA) plus the known
     * list. Needs package visibility (QUERY_ALL_PACKAGES / <queries> in the manifest).
     */
    fun cameraPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val found = listOf(MediaStore.ACTION_VIDEO_CAPTURE, MediaStore.INTENT_ACTION_VIDEO_CAMERA).flatMap { action ->
            runCatching {
                pm.queryIntentActivities(android.content.Intent(action), android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                    .map { it.activityInfo.packageName }
            }.getOrDefault(emptyList())
        }
        return KNOWN_CAMERA_PACKAGES + found
    }

    /**
     * True when the video was filmed by the phone's camera:
     *  - created by a camera app → yes;
     *  - created by any other app (browser download, Zalo/Messenger save, Gallery editor, CapCut…) → no;
     *  - creator unknown (restored from backup / copied over USB) → yes only if it has a camera-style name.
     */
    fun isFromCamera(ownerPackage: String?, displayName: String, cameraPackages: Set<String>): Boolean = when {
        ownerPackage.isNullOrBlank() -> hasCameraName(displayName)
        else -> ownerPackage in cameraPackages
    }

    // ---- recording time -------------------------------------------------------------------------

    /** Earliest plausible recording date; filters out 1904/1970 "zero" container timestamps. */
    private const val MIN_VALID = 946_684_800_000L // 2000-01-01

    private val fileStamp = Regex("""(?:^|[^0-9])((?:19|20)\d{6})[_\-]?(\d{6})\d{0,3}(?:[^0-9]|$)""")
    private val fileFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /**
     * Camera file names carry the local start time: Xiaomi "VID_20261109_175130.mp4",
     * Pixel "PXL_20261109_175130123.mp4", Samsung "20261109_175130.mp4". Null if no stamp.
     */
    fun timeFromFileName(name: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val m = fileStamp.find(name) ?: return null
        return runCatching {
            LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], fileFmt).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()?.takeIf { it >= MIN_VALID && it <= System.currentTimeMillis() + 86_400_000L }
    }

    /** MediaMetadataRetriever DATE, e.g. "20261109T105130.000Z" (container creation time, UTC). */
    fun parseMetadataDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        val patterns = listOf("yyyyMMdd'T'HHmmss.SSSX", "yyyyMMdd'T'HHmmssX", "yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'")
        for (p in patterns) {
            val t = runCatching {
                if (p.endsWith("'Z'")) LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p)).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
                else ZonedDateTime.parse(s, DateTimeFormatter.ofPattern(p)).toInstant().toEpochMilli()
            }.getOrNull()
            if (t != null && t >= MIN_VALID) return t
        }
        return null
    }

    /**
     * When the video was filmed. Order:
     *  1. timestamp in the camera's file name (local time the recording started),
     *  2. MediaStore DATE_TAKEN (MediaProvider reads it from the video's own metadata),
     *  3. the container's creation time read directly ([metadataTime], only if 1–2 are missing),
     *  4. file last-modified time (≈ when recording ended).
     * Upload time / DATE_ADDED is never used, so copied or backfilled videos keep their real date.
     */
    fun recordedAt(
        displayName: String,
        dateTakenMs: Long,
        dateModifiedSec: Long,
        metadataTime: () -> Long? = { null },
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = timeFromFileName(displayName, zone)
        ?: dateTakenMs.takeIf { it >= MIN_VALID }
        ?: metadataTime()
        ?: (dateModifiedSec * 1000).takeIf { it >= MIN_VALID }
        ?: System.currentTimeMillis()

    private fun metadataTime(context: Context, mediaId: Long): Long? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uriOf(mediaId))
            parseMetadataDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
        }
    }.getOrNull()

    // ---- queries ---------------------------------------------------------------------------------

    private val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.MIME_TYPE,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.RELATIVE_PATH,
        MediaStore.Video.Media.OWNER_PACKAGE_NAME,
    )

    private fun read(context: Context, c: Cursor): List<CameraVideo> {
        val cameraPkgs = cameraPackages(context)
        val owner = c.getColumnIndexOrThrow(MediaStore.Video.Media.OWNER_PACKAGE_NAME)
        val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val mime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
        val taken = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
        val added = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val modified = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        val dur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val path = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
        val out = ArrayList<CameraVideo>(c.count)
        while (c.moveToNext()) {
            if (!isCameraFolder(c.getString(path))) continue
            val mediaId = c.getLong(id)
            val displayName = c.getString(name) ?: "video"
            val ownerPkg = c.getString(owner)
            out += CameraVideo(
                ownerPackage = ownerPkg,
                fromCamera = isFromCamera(ownerPkg, displayName, cameraPkgs),
                mediaId = mediaId,
                displayName = displayName,
                sizeBytes = c.getLong(size),
                mimeType = c.getString(mime) ?: "video/mp4",
                recordedAt = recordedAt(displayName, c.getLong(taken), c.getLong(modified), { metadataTime(context, mediaId) }),
                durationMs = c.getLong(dur),
                addedEpochSec = c.getLong(added),
            )
        }
        return out
    }

    /**
     * Finished (non-pending) videos in DCIM/Camera added on/after [sinceEpochSec], oldest first —
     * including ones other apps saved there; check [CameraVideo.fromCamera].
     */
    fun query(context: Context, sinceEpochSec: Long): List<CameraVideo> {
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Video.Media.DATE_ADDED} >= ? AND " +
            "${MediaStore.Video.Media.IS_PENDING} = 0 AND ${MediaStore.Video.Media.SIZE} > 0"
        return context.contentResolver.query(
            collection, projection, selection, arrayOf("DCIM/Camera%", sinceEpochSec.toString()),
            "${MediaStore.Video.Media.DATE_ADDED} ASC",
        )?.use { read(context, it) } ?: emptyList()
    }

    /** Every DCIM/Camera video, newest recording first (the Video tab hides non-camera ones by default). */
    fun listAll(context: Context): List<CameraVideo> = query(context, 0).sortedByDescending { it.recordedAt }

    /** Current size, or null if the video was deleted / moved out of reach. */
    fun currentSize(context: Context, mediaId: Long): Long? =
        context.contentResolver.query(
            uriOf(mediaId), arrayOf(MediaStore.Video.Media.SIZE), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** System-generated thumbnail (frame from the video), or null. Call off the main thread. */
    fun thumbnail(context: Context, mediaId: Long, px: Int = 320): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(uriOf(mediaId), Size(px, px), null) }.getOrNull()
}
