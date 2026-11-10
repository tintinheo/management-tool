package com.myguitarplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.myguitarplayer.audio.AudioConfig
import com.myguitarplayer.platform.Platform
import com.myguitarplayer.ui.common.BackDispatcher
import com.myguitarplayer.ui.theme.GuitarTheme
import java.awt.Dimension

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    Platform.install(DesktopPlatform)
    CrashLog.install()
    AudioConfig.load()

    application {
        val state = rememberWindowState(size = DpSize(1100.dp, 820.dp), position = WindowPosition.PlatformDefault)
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "MyGuitarPlayer",
            icon = rememberVectorPainter(remember { appIcon() }),
            onKeyEvent = { e ->
                e.type == KeyEventType.KeyDown && e.key == Key.Escape && BackDispatcher.back()
            },
        ) {
            window.minimumSize = Dimension(420, 640)
            GuitarTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Press) { if (it.buttons.isBackPressed) BackDispatcher.back() },
                ) { App() }
            }
        }
    }
}

/** Same gold pick + three strings as the Android launcher icon, on the app's ink background. */
private fun appIcon(): ImageVector = ImageVector.Builder(
    name = "MyGuitarPlayer",
    defaultWidth = 108.dp,
    defaultHeight = 108.dp,
    viewportWidth = 108f,
    viewportHeight = 108f,
).apply {
    path(fill = SolidColor(Color(0xFF0E0E10))) {
        moveTo(20f, 4f); lineTo(88f, 4f); quadTo(104f, 4f, 104f, 20f); lineTo(104f, 88f); quadTo(104f, 104f, 88f, 104f)
        lineTo(20f, 104f); quadTo(4f, 104f, 4f, 88f); lineTo(4f, 20f); quadTo(4f, 4f, 20f, 4f); close()
    }
    addPath(
        pathData = addPathNodes("M54,22C70,22 84,27 84,40C84,56 65,77 54,88C43,77 24,56 24,40C24,27 38,22 54,22z"),
        fill = SolidColor(Color(0xFFE8B84A)),
    )
    addPath(
        pathData = addPathNodes("M43,33L43,64M54,30L54,74M65,33L65,64"),
        stroke = SolidColor(Color(0xFF0E0E10)),
        strokeLineWidth = 2.6f,
    )
}.build()
