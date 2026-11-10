package com.ytsync.youtube

import com.ytsync.media.CameraVideos
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class VideoMetadataTest {
    private val taken = 1_762_680_660_000L // 2025-11-09T09:31:00Z

    @Test fun privateNotForKidsAndAgeRestricted() {
        val body = VideoMetadata.build("t", "VID_1.mp4", taken, ageRestrict = true)
        val status = body["status"]!!.jsonObject
        assertEquals("private", status["privacyStatus"]!!.jsonPrimitive.content)
        assertFalse(status["selfDeclaredMadeForKids"]!!.jsonPrimitive.boolean)
        assertEquals(
            "ytAgeRestricted",
            body["contentDetails"]!!.jsonObject["contentRating"]!!.jsonObject["ytRating"]!!.jsonPrimitive.content,
        )
        assertEquals("2025-11-09T09:31:00Z", body["recordingDetails"]!!.jsonObject["recordingDate"]!!.jsonPrimitive.content)
        assertTrue(VideoMetadata.parts(true).split(',').contains("contentDetails"))
    }

    @Test fun ratingOmittedWhenDisabled() {
        val body = VideoMetadata.build("t", "a.mp4", taken, ageRestrict = false)
        assertNull(body["contentDetails"])
        assertFalse(VideoMetadata.parts(false).contains("contentDetails"))
        assertEquals("private", body["status"]!!.jsonObject["privacyStatus"]!!.jsonPrimitive.content)
    }

    @Test fun titleTemplateStartsWithYyyymmdd() {
        val hcm = ZoneId.of("Asia/Ho_Chi_Minh") // taken = 2025-11-09 16:31:00 local
        val file = "VID_20251109_163100.mp4"
        assertEquals("20251109 Video 16:31", VideoMetadata.title("", taken, file, hcm))
        assertEquals("20251109 Video 16:31", VideoMetadata.title(VideoMetadata.DEFAULT_TEMPLATE, taken, file, hcm))
        assertEquals("20251109 Du lịch Đà Lạt", VideoMetadata.title("{YYYYMMDD} Du lịch Đà Lạt", taken, file, hcm))
        assertEquals("20251109_1631 VID_20251109_163100", VideoMetadata.title("{YYYYMMDD}_{HH}{mm} {name}", taken, file, hcm))
        assertEquals("2025-11-09 16:31:00", VideoMetadata.title("{YYYY}-{MM}-{DD} {HH}:{mm}:{ss}", taken, file, hcm))
        assertEquals("20251109 ab", VideoMetadata.title("{YYYYMMDD}  <a>b ", taken, file, hcm))
        assertEquals(100, VideoMetadata.title("{YYYYMMDD} " + "x".repeat(150), taken, file, hcm).length)
    }

    @Test fun cameraFolderMatching() {
        assertTrue(CameraVideos.isCameraFolder("DCIM/Camera/"))
        assertTrue(CameraVideos.isCameraFolder("DCIM/Camera"))
        assertTrue(CameraVideos.isCameraFolder("DCIM/Camera/2026/"))
        assertFalse(CameraVideos.isCameraFolder("DCIM/CameraBackup/"))
        assertFalse(CameraVideos.isCameraFolder("DCIM/ScreenRecorder/"))
        assertFalse(CameraVideos.isCameraFolder("Movies/"))
        assertFalse(CameraVideos.isCameraFolder(null))
    }

    @Test fun rangeHeaderParsing() {
        assertEquals(0, YouTubeApi.nextByteFromRange(null))
        assertEquals(524288, YouTubeApi.nextByteFromRange("bytes=0-524287"))
        assertEquals(0, YouTubeApi.nextByteFromRange("garbage"))
    }
}
