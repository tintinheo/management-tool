package com.ytsync.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSourceTest {
    private val cams = CameraVideos.KNOWN_CAMERA_PACKAGES

    @Test fun createdByCameraAppCounts() {
        assertTrue(CameraVideos.isFromCamera("com.android.camera", "VID_20261109_175130.mp4", cams))
        // even if the camera named it oddly
        assertTrue(CameraVideos.isFromCamera("com.android.camera", "clip.mp4", cams))
    }

    @Test fun savedByOtherAppsIsExcludedEvenWithCameraLikeName() {
        assertFalse(CameraVideos.isFromCamera("com.zing.zalo", "VID_20261109_175130.mp4", cams))   // Zalo save
        assertFalse(CameraVideos.isFromCamera("com.android.chrome", "video.mp4", cams))             // download
        assertFalse(CameraVideos.isFromCamera("com.miui.gallery", "VID_20261109_175130_edit.mp4", cams)) // Gallery editor
        assertFalse(CameraVideos.isFromCamera("com.lemon.lvoverseas", "CapCut_20261109.mp4", cams))
    }

    @Test fun unknownOwnerFallsBackToCameraFileName() {
        // restored from backup / copied over USB: Android no longer knows the creator
        assertTrue(CameraVideos.isFromCamera(null, "VID_20261109_175130.mp4", cams))
        assertTrue(CameraVideos.isFromCamera(null, "PXL_20261109_175130123.mp4", cams))
        assertTrue(CameraVideos.isFromCamera("", "20261109_175130.mp4", cams))
        assertFalse(CameraVideos.isFromCamera(null, "funny_cat.mp4", cams))
        assertFalse(CameraVideos.isFromCamera(null, "snaptik_7301234567890.mp4", cams))
    }
}
