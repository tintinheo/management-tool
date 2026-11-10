package com.ytsync.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ytsync.data.Settings
import com.ytsync.data.UploadItem
import com.ytsync.data.UploadState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Plain Application: the real App schedules WorkManager, which isn't initialised under Robolectric.
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi", application = android.app.Application::class)
class HomeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val noop = object : HomeActions {
        override fun connect() {}
        override fun requestVideoPermission() {}
        override fun requestNotificationPermission() {}
        override fun fixBattery() {}
        override fun openAutostart() {}
        override fun setEnabled(on: Boolean) {}
        override fun setWifiOnly(on: Boolean) {}
        override fun setChargingOnly(on: Boolean) {}
        override fun setAgeRestrict(on: Boolean) {}
        override fun setTitleTemplate(text: String) {}
        override fun scanNow() {}
        override fun backfill(days: Int?) {}
        override fun retry(item: UploadItem) {}
        override fun retryAllFailed() {}
        override fun skip(item: UploadItem) {}
        override fun openStudio(item: UploadItem) {}
        override fun dismissMessage() {}
    }

    @Test fun firstRun() {
        compose.setContent { YtTheme { HomeScreen(HomeState(isXiaomi = true), noop) } }
        compose.onRoot().captureRoboImage("build/screenshots/home_first_run.png")
    }

    @Test fun syncing() {
        val t = 1_762_680_660_000L
        val items = listOf(
            UploadItem(4, "VID_20251109_163100.mp4", 1_250_000_000, "video/mp4", t, state = UploadState.UPLOADING, bytesSent = 480_000_000),
            UploadItem(3, "VID_20251109_120512.mp4", 85_000_000, "video/mp4", t - 16_000_000, state = UploadState.QUEUED),
            UploadItem(2, "VID_20251108_210044.mp4", 320_000_000, "video/mp4", t - 70_000_000, state = UploadState.DONE, videoId = "abc", ageRestricted = true),
            UploadItem(1, "VID_20251108_090010.mp4", 40_000_000, "video/mp4", t - 110_000_000, state = UploadState.FAILED, error = "HTTP 400 invalidTitle: bad title"),
        )
        val state = HomeState(
            settings = Settings(enabled = true, connected = true, channelTitle = "Kênh của tôi"),
            items = items, hasVideoPermission = true, hasNotificationPermission = true, batteryUnrestricted = true, isXiaomi = true,
        )
        compose.setContent { YtTheme { HomeScreen(state, noop) } }
        compose.onRoot().captureRoboImage("build/screenshots/home_syncing.png")
    }
}
