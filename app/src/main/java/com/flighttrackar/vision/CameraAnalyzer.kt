package com.flighttrackar.vision

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.flighttrackar.model.CameraIntrinsics
import com.flighttrackar.model.VisionMode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * CameraX [ImageAnalysis.Analyzer] adapter that maps YUV_420_888 camera frames (accounting for
 * sensor rotation and PreviewView FILL_CENTER aspect-ratio crop) into [SkyAircraftDetector]
 * with zero full-frame buffer rotation overhead.
 */
class CameraAnalyzer(
    private val detector: SkyAircraftDetector,
    private val hintsProvider: () -> List<SkyAircraftDetector.AircraftRoiHint>,
    private val visionModeProvider: () -> VisionMode,
    private val intrinsicsProvider: () -> CameraIntrinsics,
    private val onResult: (SkyAircraftDetector.FrameDetectionResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        /**
         * Maps an upright rotated pixel (rx, ry) to raw unrotated buffer coordinates (rawX, rawY).
         * Exposed as internal/public helper for unit testing rotation math.
         */
        fun rotatedToRaw(
            rx: Int,
            ry: Int,
            rawWidth: Int,
            rawHeight: Int,
            rotationDegrees: Int
        ): Pair<Int, Int> {
            val rawX: Int
            val rawY: Int
            when (((rotationDegrees % 360) + 360) % 360) {
                90 -> {
                    rawX = ry
                    rawY = rawHeight - 1 - rx
                }
                180 -> {
                    rawX = rawWidth - 1 - rx
                    rawY = rawHeight - 1 - ry
                }
                270 -> {
                    rawX = rawWidth - 1 - ry
                    rawY = rx
                }
                else -> {
                    rawX = rx
                    rawY = ry
                }
            }
            return rawX.coerceIn(0, rawWidth - 1) to rawY.coerceIn(0, rawHeight - 1)
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            val mode = visionModeProvider()
            val intrinsics = intrinsicsProvider()
            val hints = hintsProvider()

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer

            val rawW = image.width
            val rawH = image.height
            val rotDeg = image.imageInfo.rotationDegrees

            val uprightW = if (rotDeg == 90 || rotDeg == 270) rawH else rawW
            val uprightH = if (rotDeg == 90 || rotDeg == 270) rawW else rawH

            // Compute exact FILL_CENTER visible crop rectangle inside the upright camera image
            // so [0..1] coordinates match PreviewView pixel-for-pixel!
            val vw = intrinsics.viewportWidthPx.coerceAtLeast(1).toFloat()
            val vh = intrinsics.viewportHeightPx.coerceAtLeast(1).toFloat()
            val scale = max(vw / uprightW.toFloat(), vh / uprightH.toFloat())
            val visibleW = (vw / scale).roundToInt().coerceIn(32, uprightW)
            val visibleH = (vh / scale).roundToInt().coerceIn(32, uprightH)
            val cropLeft = ((uprightW - visibleW) / 2).coerceAtLeast(0)
            val cropTop = ((uprightH - visibleH) / 2).coerceAtLeast(0)

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride

            val frame = object : SkyAircraftDetector.UprightFrame {
                override val width: Int = visibleW
                override val height: Int = visibleH

                override fun getY(x: Int, y: Int): Int {
                    val (rawX, rawY) = rotatedToRaw(cropLeft + x, cropTop + y, rawW, rawH, rotDeg)
                    val offset = rawY * yRowStride + rawX * yPixelStride
                    return if (offset in 0 until yBuf.limit()) (yBuf.get(offset).toInt() and 0xFF) else 128
                }

                override fun getU(x: Int, y: Int): Int {
                    val (rawX, rawY) = rotatedToRaw(cropLeft + x, cropTop + y, rawW, rawH, rotDeg)
                    val offset = (rawY shr 1) * uRowStride + (rawX shr 1) * uPixelStride
                    return if (offset in 0 until uBuf.limit()) (uBuf.get(offset).toInt() and 0xFF) else 128
                }

                override fun getV(x: Int, y: Int): Int {
                    val (rawX, rawY) = rotatedToRaw(cropLeft + x, cropTop + y, rawW, rawH, rotDeg)
                    val offset = (rawY shr 1) * vRowStride + (rawX shr 1) * vPixelStride
                    return if (offset in 0 until vBuf.limit()) (vBuf.get(offset).toInt() and 0xFF) else 128
                }
            }

            val result = detector.analyzeFrame(
                frame = frame,
                hints = hints,
                visionMode = mode,
                timestampMs = System.currentTimeMillis()
            )
            onResult(result)
        } finally {
            image.close()
        }
    }
}
