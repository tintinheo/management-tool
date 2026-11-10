package com.remotecam.lan

import com.remotecam.remote.CameraStatus
import java.util.Locale

object StatusJson {
    fun encode(camera: CameraStatus, viewers: Int): String = buildString {
        append('{')
        append("\"active\":").append(camera.active).append(',')
        append("\"photos\":").append(camera.photos).append(',')
        append("\"lens\":\"").append(if (camera.backCamera) "back" else "front").append("\",")
        append("\"zoom\":").append(String.format(Locale.US, "%.2f", camera.zoom)).append(',')
        append("\"countdown\":").append(camera.countdown?.toString() ?: "null").append(',')
        append("\"latencyMs\":").append(camera.lastLatencyMs?.toString() ?: "null").append(',')
        append("\"viewers\":").append(viewers)
        append('}')
    }
}
