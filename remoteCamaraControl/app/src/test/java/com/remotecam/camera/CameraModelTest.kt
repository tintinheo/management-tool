package com.remotecam.camera

import com.remotecam.lan.StatusJson
import com.remotecam.remote.CameraStatus
import com.remotecam.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModelTest {

    private val xiaomiLike = CameraCaps(
        modes = listOf(CaptureMode.PHOTO, CaptureMode.NIGHT, CaptureMode.VIDEO),
        photoFormats = listOf(PhotoFormat.JPEG, PhotoFormat.RAW, PhotoFormat.RAW_JPEG),
        standardPhotoSize = Dim(4080, 3060),
        maxPhotoSize = Dim(8160, 6120),
        videoQualities = mapOf(
            VideoProfile.SDR to listOf(VideoQuality.FHD, VideoQuality.UHD),
            VideoProfile.HLG to listOf(VideoQuality.FHD),
            VideoProfile.LOG to emptyList(),
        ),
        fps60 = true,
        flash = true,
    )

    @Test fun settingsParseAndRejectUnknownValues() {
        val c = CameraConfig()
        assertEquals(CaptureMode.VIDEO, c.with("mode", "video")?.mode)
        assertEquals(Lens.FRONT, c.with("lens", "toggle")?.lens)
        assertEquals(Lens.BACK, c.with("lens", "toggle")!!.with("lens", "toggle")?.lens)
        assertEquals(10, c.with("timer", "10")?.timerSeconds)
        assertNull(c.with("timer", "7"))
        assertNull(c.with("fps", "120"))
        assertNull(c.with("mode", "cinema"))
        assertNull(c.with("unknown", "x"))
    }

    @Test fun configSurvivesSaveAndLoad() {
        val c = CameraConfig(mode = CaptureMode.VIDEO, photoFormat = PhotoFormat.RAW_JPEG, videoQuality = VideoQuality.UHD,
            videoProfile = VideoProfile.HLG, videoFps = 60, audio = false, timerSeconds = 10)
        assertEquals(c, CameraConfig.fromMap(c.toMap()))
        assertEquals(CameraConfig(), CameraConfig.fromMap(mapOf("mode" to "garbage")))
    }

    @Test fun onlySomeChangesNeedTheCameraRebound() {
        val c = CameraConfig()
        assertFalse(c.needsRebind(c.copy(flash = FlashSetting.ON, timerSeconds = 10, audio = false)))
        assertTrue(c.needsRebind(c.copy(mode = CaptureMode.VIDEO)))
        assertTrue(c.needsRebind(c.copy(photoFormat = PhotoFormat.RAW)))
    }

    @Test fun normalizedFallsBackToWhatTheLensCanDo() {
        // Portrait not available on this lens -> plain photo.
        assertEquals(CaptureMode.PHOTO, CameraConfig(mode = CaptureMode.PORTRAIT).normalized(xiaomiLike).mode)
        // Extensions only produce JPEG at standard size, but the user's choice is kept in "wanted".
        val night = CameraConfig(mode = CaptureMode.NIGHT, photoFormat = PhotoFormat.RAW, photoRes = PhotoRes.MAX).normalized(xiaomiLike)
        assertEquals(PhotoFormat.JPEG, night.photoFormat)
        assertEquals(PhotoRes.STANDARD, night.photoRes)
        // RAW and max resolution are kept in normal photo mode.
        val raw = CameraConfig(photoFormat = PhotoFormat.RAW_JPEG, photoRes = PhotoRes.MAX).normalized(xiaomiLike)
        assertEquals(PhotoFormat.RAW_JPEG, raw.photoFormat)
        assertEquals(PhotoRes.MAX, raw.photoRes)
        // HLG has no 4K on this lens -> HLG 1080p; Log unsupported -> SDR.
        val hlg = CameraConfig(mode = CaptureMode.VIDEO, videoProfile = VideoProfile.HLG, videoQuality = VideoQuality.UHD).normalized(xiaomiLike)
        assertEquals(VideoProfile.HLG to VideoQuality.FHD, hlg.videoProfile to hlg.videoQuality)
        assertEquals(VideoProfile.SDR, CameraConfig(videoProfile = VideoProfile.LOG).normalized(xiaomiLike).videoProfile)
        // A lens without flash or 60 fps.
        val plain = CameraConfig(flash = FlashSetting.ON, videoFps = 60).normalized(CameraCaps())
        assertEquals(FlashSetting.OFF to 30, plain.flash to plain.videoFps)
    }

    @Test fun controlRequestsAreValidated() {
        assertEquals(ControlRequest("mode", "video"), ControlRequest.parse(mapOf("key" to "mode", "value" to "video")))
        assertEquals(ControlRequest("zoom", "2.5"), ControlRequest.parse(mapOf("key" to "zoom", "value" to "2.5")))
        assertEquals(ControlRequest("focus", x = .5f, y = .1f), ControlRequest.parse(mapOf("key" to "focus", "x" to "0.5", "y" to "0.1")))
        assertNull(ControlRequest.parse(mapOf("key" to "zoom", "value" to "-1")))
        assertNull(ControlRequest.parse(mapOf("key" to "focus", "x" to "0.5")))
        assertNull(ControlRequest.parse(mapOf("key" to "photoFormat", "value" to "heic")))
        assertNull(ControlRequest.parse(mapOf("value" to "video")))
    }

    @Test fun focusPointsMapBackToTheSensor() {
        assertEquals(.2f to .7f, Metering.toBuffer(.2f, .7f, 0))
        // Portrait phone, sensor rotated 90°: top-left of the screen is bottom-left of the sensor.
        assertEquals(0f to 1f, Metering.toBuffer(0f, 0f, 90))
        assertEquals(1f to 0f, Metering.toBuffer(0f, 0f, 270))
        assertEquals(.75f to .5f, Metering.toBuffer(.25f, .5f, 180))
    }

    @Test fun logCurveIsMonotonicAndFlat() {
        val p = LogCurve.points()
        val outs = (0 until p.size / 2).map { p[it * 2 + 1] }
        assertTrue(outs.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0.03f, outs.first(), 1e-4f)
        assertEquals(0.95f, outs.last(), 1e-4f)
        // 18% grey lands around 40% like manufacturer log modes.
        val i = (0.18f * (outs.size - 1)).toInt()
        assertTrue(outs[i] in 0.35f..0.5f)
    }

    @Test fun bandTextFollowsWhatIsHappening() {
        assertEquals("RemoteCam · Ảnh · 4 ảnh", EngineState(photos = 4).bandText())
        assertEquals("● REC 01:05", EngineState(recordingSeconds = 65).bandText())
        assertEquals("Hẹn giờ: 2", EngineState(countdown = 2).bandText())
    }

    @Test fun jsonWriterEscapesAndFormats() {
        assertEquals(
            """{"a":"x\"y\n","b":[1,2.5,true,null],"c":{"d":0.333}}""",
            Json.write(mapOf("a" to "x\"y\n", "b" to listOf(1, 2.5f, true, null), "c" to mapOf("d" to 1.0 / 3))),
        )
    }

    @Test fun statusJsonHasWhatThePageReads() {
        val state = EngineState(ready = true, caps = xiaomiLike, photoSize = Dim(8160, 6120), recordingSeconds = 12)
        val json = StatusJson.encode(CameraStatus(active = true, state = state), viewers = 2)
        for (part in listOf(
            "\"active\":true", "\"viewers\":2", "\"recording\":12", "\"config\":{\"mode\":\"photo\"",
            "\"photoSize\":\"8160×6120 (49.9MP)\"", "{\"id\":\"night\",\"label\":\"Đêm\"}", "\"fps\":[30,60]",
            "{\"id\":\"raw_jpeg\",\"label\":\"RAW + JPEG\"}",
        )) {
            assertTrue("missing $part in $json", json.contains(part))
        }
    }
}
