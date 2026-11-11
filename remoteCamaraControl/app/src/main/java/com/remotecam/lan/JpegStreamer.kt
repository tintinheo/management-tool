package com.remotecam.lan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Encodes upright frames into JPEG for every stream profile that has viewers and is due a frame.
 * Must be used from a single thread.
 */
class FrameEncoder(private val hubs: StreamHubs) {
    private val lastAt = EnumMap<StreamProfile, Long>(StreamProfile::class.java)
    private val buffer = ByteArrayOutputStream(128 * 1024)

    fun dueProfiles(now: Long): List<StreamProfile> =
        hubs.active().filter { now - (lastAt[it] ?: 0L) >= it.intervalMs }

    /** [upright] may be larger than needed; each profile gets its own downscaled copy. */
    fun encode(upright: Bitmap, due: List<StreamProfile>, now: Long) {
        for (profile in due.sortedByDescending { it.maxEdge }) {
            val scale = profile.maxEdge.toFloat() / max(upright.width, upright.height)
            val frame = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    upright,
                    (upright.width * scale).roundToInt(),
                    (upright.height * scale).roundToInt(),
                    true,
                )
            } else {
                upright
            }
            buffer.reset()
            frame.compress(Bitmap.CompressFormat.JPEG, profile.quality, buffer)
            if (frame !== upright) frame.recycle()
            hubs.hub(profile).publish(buffer.toByteArray())
            lastAt[profile] = now
        }
    }
}

/** CameraX analyzer feeding [FrameEncoder]. Returns immediately when nobody is watching. */
class JpegStreamer(private val encoder: FrameEncoder) : ImageAnalysis.Analyzer {

    /** Last rotation seen, used to map browser tap-to-focus points back onto the sensor. */
    @Volatile var rotationDegrees: Int = 0
        private set

    override fun analyze(image: ImageProxy) {
        image.use {
            rotationDegrees = it.imageInfo.rotationDegrees
            val now = SystemClock.uptimeMillis()
            val due = encoder.dueProfiles(now)
            if (due.isEmpty()) return

            val source = it.toBitmap()
            val largest = due.maxOf { p -> p.maxEdge }
            val scale = largest.toFloat() / max(source.width, source.height)
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                postRotate(rotationDegrees.toFloat())
            }
            val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            encoder.encode(upright, due, now)
            if (upright !== source) upright.recycle()
            source.recycle()
        }
    }
}
