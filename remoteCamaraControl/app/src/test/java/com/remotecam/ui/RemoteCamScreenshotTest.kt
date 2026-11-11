package com.remotecam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.remotecam.camera.CaptureMode
import com.remotecam.remote.LogEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class RemoteCamScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val fakePreview = @androidx.compose.runtime.Composable {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF3B4A5A), Color(0xFF1D2229)))),
        )
    }

    private val log = listOf(
        LogEntry("20:41:07.512", "✓ Đã lưu RemoteCam_20261109_204107_210.jpg (612 ms từ lúc nhận lệnh)"),
        LogEntry("20:41:06.900", "onPause ← com.samsung.android.app.watchmanager → Chụp"),
        LogEntry("20:40:51.203", "onSkipToNext ← com.samsung.android.app.watchmanager → Đổi camera"),
        LogEntry("20:40:12.004", "Âm thanh im lặng: bật (focus OK)"),
        LogEntry("20:40:11.998", "Session bật. Mở mục nhạc trên Fit 3 và bấm thử các nút."),
    )

    @Test fun running() {
        compose.setContent {
            RemoteCamScreen(
                state = ScreenState(
                    camera = CameraUiState(photos = 3, zoom = 1.6f, lastLatencyMs = 612),
                    sessionRunning = true,
                    serverUrls = listOf("http://10.181.24.97:8080/?k=482913  (swlan0)"),
                    viewers = 1,
                    log = log,
                ),
                actions = ScreenActions(),
                preview = fakePreview,
            )
        }
        compose.onRoot().captureRoboImage("build/screenshots/remotecam_running.png")
    }

    @Test fun countdownNoSession() {
        compose.setContent {
            RemoteCamScreen(
                state = ScreenState(
                    camera = CameraUiState(
                        countdown = 2,
                        backCamera = false,
                        mode = CaptureMode.VIDEO,
                        modes = CaptureMode.entries,
                        recordingSeconds = 75,
                        detail = "4K 30fps HLG 10-bit",
                    ),
                    sessionRunning = false,
                    remoteVolume = true,
                    keepPlaying = false,
                ),
                actions = ScreenActions(),
                preview = fakePreview,
            )
        }
        compose.onRoot().captureRoboImage("build/screenshots/remotecam_countdown.png")
    }
}
