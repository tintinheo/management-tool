package com.ytsync.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ytsync.data.UploadItem
import com.ytsync.data.UploadState
import com.ytsync.media.CameraVideo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1100dp-xxhdpi", application = android.app.Application::class)
class GalleryScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val noop = object : GalleryActions {
        override fun refreshGallery() {}
        override fun toggle(id: Long) {}
        override fun setSelected(ids: Collection<Long>, selected: Boolean) {}
        override fun clearSelection() {}
        override fun renameGroup(ids: List<Long>, template: String) {}
        override fun uploadGroup(ids: List<Long>) {}
        override fun openStudio(videoId: String) {}
        override fun requestVideoPermission() {}
        override suspend fun thumbnail(id: Long): ImageBitmap? = null
    }

    private fun t(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, d, h, m, 12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun v(id: Long, time: Long, dur: Long, size: Long) =
        CameraVideo(id, "VID_${id}.mp4", size, "video/mp4", time, dur, 0)

    private val entries = listOf(
        GalleryEntry(v(5, t(9, 17, 51), 83_000, 210_000_000), null),
        GalleryEntry(v(4, t(9, 16, 31), 1_520_000, 1_250_000_000), UploadItem(4, "VID_4.mp4", 1, "video/mp4", t(9, 16, 31), state = UploadState.UPLOADING, title = "20261109 Video 16:31")),
        GalleryEntry(v(3, t(9, 9, 5), 41_000, 85_000_000), UploadItem(3, "VID_3.mp4", 1, "video/mp4", t(9, 9, 5), state = UploadState.IDLE, titleOverride = "20261109 Đà Lạt 2")),
        GalleryEntry(v(2, t(8, 21, 0), 300_000, 320_000_000), UploadItem(2, "VID_2.mp4", 1, "video/mp4", t(8, 21, 0), state = UploadState.DONE, videoId = "abc", title = "20261108 Đà Lạt 1")),
        GalleryEntry(v(1, t(8, 9, 0), 12_000, 40_000_000), UploadItem(1, "VID_1.mp4", 1, "video/mp4", t(8, 9, 0), state = UploadState.QUEUED)),
    )

    @Test fun list() {
        compose.setContent { YtTheme { GalleryScreen(GalleryState(entries = entries), noop) } }
        compose.onRoot().captureRoboImage("build/screenshots/gallery_list.png")
    }

    @Test fun selecting() {
        compose.setContent { YtTheme { GalleryScreen(GalleryState(entries = entries, selected = setOf(5, 3, 2)), noop) } }
        compose.onRoot().captureRoboImage("build/screenshots/gallery_selecting.png")
    }
}
