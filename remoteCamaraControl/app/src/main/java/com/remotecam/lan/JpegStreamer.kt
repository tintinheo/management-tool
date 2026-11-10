package com.remotecam.lan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * CameraX analyzer that turns frames into upright JPEGs for the browser preview. Does nothing while
 * nobody is watching, and caps the frame rate so encoding does not compete with photo capture.
 */
class JpegStreamer(
    private val hub: FrameHub,
    private val maxEdge: Int = 960,
    private val quality: Int = 60,
    private val minFrameIntervalMs: Long = 66, // ~15 fps
) : ImageAnalysis.Analyzer {

    private var lastFrameAt = 0L
    private val buffer = ByteArrayOutputStream(128 * 1024)

    override fun analyze(image: ImageProxy) {
        image.use {
            if (hub.viewers.get() == 0) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt < minFrameIntervalMs) return
            lastFrameAt = now

            val source = it.toBitmap()
            val scale = maxEdge.toFloat() / max(source.width, source.height)
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                postRotate(it.imageInfo.rotationDegrees.toFloat())
            }
            val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            buffer.reset()
            upright.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            if (upright !== source) upright.recycle()
            source.recycle()
            hub.publish(buffer.toByteArray())
        }
    }
}
