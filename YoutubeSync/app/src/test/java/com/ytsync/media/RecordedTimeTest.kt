package com.ytsync.media

import com.ytsync.data.UploadState
import com.ytsync.ui.GalleryEntry
import com.ytsync.ui.groupTitles
import com.ytsync.youtube.VideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RecordedTimeTest {
    private val hcm = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(hcm).toInstant().toEpochMilli()

    @Test fun cameraFileNamesGiveLocalStartTime() {
        val t = local(2026, 11, 9, 17, 51, 30)
        assertEquals(t, CameraVideos.timeFromFileName("VID_20261109_175130.mp4", hcm))       // Xiaomi
        assertEquals(t, CameraVideos.timeFromFileName("PXL_20261109_175130123.mp4", hcm))    // Pixel (+ms)
        assertEquals(t, CameraVideos.timeFromFileName("20261109_175130.mp4", hcm))           // Samsung
        assertEquals(t, CameraVideos.timeFromFileName("VID_20261109_175130_HSR_120.mp4", hcm))
        assertNull(CameraVideos.timeFromFileName("my holiday.mp4", hcm))
        assertNull(CameraVideos.timeFromFileName("VID_20261399_999999.mp4", hcm)) // invalid date
    }

    @Test fun metadataDateIsUtc() {
        val utc = LocalDateTime.of(2026, 11, 9, 10, 51, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(utc, CameraVideos.parseMetadataDate("20261109T105130.000Z"))
        assertEquals(utc, CameraVideos.parseMetadataDate("20261109T105130Z"))
        assertNull(CameraVideos.parseMetadataDate("19040101T000000.000Z")) // "zero" container date
        assertNull(CameraVideos.parseMetadataDate(null))
    }

    @Test fun recordedAtPriorityNeverUsesUploadOrAddedTime() {
        val fromName = local(2026, 11, 9, 17, 51, 30)
        val taken = local(2026, 11, 9, 17, 52, 0)
        val meta = local(2026, 11, 9, 17, 53, 0)
        val modifiedSec = local(2026, 11, 9, 18, 0, 0) / 1000

        // 1. file name wins
        assertEquals(fromName, CameraVideos.recordedAt("VID_20261109_175130.mp4", taken, modifiedSec, { meta }, hcm))
        // 2. no stamp in the name → MediaStore DATE_TAKEN (read from the video's metadata)
        assertEquals(taken, CameraVideos.recordedAt("clip.mp4", taken, modifiedSec, { meta }, hcm))
        // 3. DATE_TAKEN missing → container creation time
        assertEquals(meta, CameraVideos.recordedAt("clip.mp4", 0, modifiedSec, { meta }, hcm))
        // 4. nothing else → last-modified (≈ end of recording)
        assertEquals(modifiedSec * 1000, CameraVideos.recordedAt("clip.mp4", 0, modifiedSec, { null }, hcm))
    }

    @Test fun groupRenameNumbersInRecordingOrder() {
        fun v(id: Long, t: Long) = GalleryEntry(CameraVideo(id, "VID_$id.mp4", 1, "video/mp4", t, 1000, 0), null)
        val a = v(1, local(2026, 11, 9, 9, 0, 0))
        val b = v(2, local(2026, 11, 9, 8, 0, 0))
        val c = v(3, local(2026, 11, 8, 23, 0, 0))
        val titles = groupTitles(listOf(a, b, c), "{YYYYMMDD} Đà Lạt {n}").map { it.first.id to it.second }
        val zone = ZoneId.systemDefault()
        val day = { t: Long -> java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString().replace("-", "") }
        assertEquals(listOf(3L, 2L, 1L), titles.map { it.first })
        assertEquals("${day(c.video.recordedAt)} Đà Lạt 1", titles[0].second)
        assertEquals("${day(a.video.recordedAt)} Đà Lạt 3", titles[2].second)
        assertEquals(UploadState.IDLE, a.state)
    }

    @Test fun seqIsZeroPaddedToGroupSize() {
        val t = local(2026, 11, 9, 9, 0, 0)
        assertEquals("Trip 03", VideoMetadata.title("Trip {n}", t, "", hcm, seq = 3, seqOf = 12))
        assertEquals("Trip", VideoMetadata.title("Trip {n}", t, "", hcm)) // auto-upload: no {n}
    }
}
