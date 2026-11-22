package com.flighttrackar.vision

import com.flighttrackar.model.DetectedSkyMode
import com.flighttrackar.model.VisionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SkyAircraftDetectorTest {

    private class SyntheticFrame(
        override val width: Int,
        override val height: Int,
        val yData: IntArray,
        val uVal: Int = 140,
        val vVal: Int = 118
    ) : SkyAircraftDetector.UprightFrame {
        override fun getY(x: Int, y: Int): Int = yData[y * width + x]
        override fun getU(x: Int, y: Int): Int = uVal
        override fun getV(x: Int, y: Int): Int = vVal
    }

    @Test
    fun daySkyWithDarkAircraftSilhouette_detectsAndBoxesPlane() {
        val w = 320
        val h = 480
        // Smooth bright blue-sky gradient (Y ~ 170..195)
        val yData = IntArray(w * h) { idx ->
            val y = idx / w
            (175 + (y * 20) / h).coerceIn(0, 255)
        }
        // Draw a dark airplane silhouette centered at (172, 198) (slightly offset from ADS-B hint at 160, 200)
        val planeCx = 172
        val planeCy = 198
        for (dy in -3..3) {
            for (dx in -6..6) {
                if (abs(dx) <= 6 && abs(dy) <= 1 || abs(dx) <= 1 && abs(dy) <= 3) {
                    yData[(planeCy + dy) * w + (planeCx + dx)] = 55
                }
            }
        }

        val detector = SkyAircraftDetector()
        val hint = SkyAircraftDetector.AircraftRoiHint(
            icao24 = "a12345",
            predictedXNorm = 160f / w,
            predictedYNorm = 200f / h,
            roiHalfWidthNorm = 48f / w,
            roiHalfHeightNorm = 48f / h,
            velocityXNormPerSec = 0.02f,
            velocityYNormPerSec = 0f,
            expectedSizeNorm = 12f / w,
            elevationDeg = 35.0
        )

        val result = detector.analyzeFrame(
            frame = SyntheticFrame(w, h, yData, uVal = 148, vVal = 112),
            hints = listOf(hint),
            visionMode = VisionMode.AUTO,
            timestampMs = 1000L
        )

        assertEquals(DetectedSkyMode.DAY, result.stats.activeMode)
        val det = result.detections["a12345"]
        assertNotNull("Expected visual detection on synthetic daytime plane", det)
        assertEquals(planeCx.toFloat(), det!!.centerXNorm * w, 3.0f)
        assertEquals(planeCy.toFloat(), det.centerYNorm * h, 3.0f)
        assertTrue("Confidence should be substantial, got ${det.confidence}", det.confidence > 0.45f)
    }

    @Test
    fun nightSkyWithBlinkingStrobe_detectsNightModeAndStrobe() {
        val w = 320
        val h = 480
        val detector = SkyAircraftDetector()
        val hint = SkyAircraftDetector.AircraftRoiHint(
            icao24 = "b99999",
            predictedXNorm = 150f / w,
            predictedYNorm = 180f / h,
            roiHalfWidthNorm = 48f / w,
            roiHalfHeightNorm = 48f / h,
            velocityXNormPerSec = 0.01f,
            velocityYNormPerSec = 0f,
            expectedSizeNorm = 6f / w,
            elevationDeg = 40.0
        )

        // Feed 6 frames over 1.2s with alternating steady nav light (Y=135) and bright strobe flash (Y=245)
        var lastResult: SkyAircraftDetector.FrameDetectionResult? = null
        val peakLumas = listOf(130, 245, 135, 130, 250, 140)
        for ((i, peak) in peakLumas.withIndex()) {
            val yData = IntArray(w * h) { 14 } // Dark night sky (Y = 14)
            val cx = 158 + i // Gently moving right
            val cy = 176
            for (dy in -1..1) {
                for (dx in -1..1) {
                    yData[(cy + dy) * w + (cx + dx)] = if (dx == 0 && dy == 0) peak else (peak * 0.7f).toInt()
                }
            }
            lastResult = detector.analyzeFrame(
                frame = SyntheticFrame(w, h, yData, uVal = 128, vVal = 150),
                hints = listOf(hint),
                visionMode = VisionMode.AUTO,
                timestampMs = 1000L + i * 220L
            )
        }

        val finalRes = lastResult!!
        assertEquals(DetectedSkyMode.NIGHT, finalRes.stats.activeMode)
        val det = finalRes.detections["b99999"]
        assertNotNull("Expected night detection on nav light + strobe", det)
        assertTrue("Expected strobe blink detection to trigger", det!!.strobeDetected)
        assertEquals(162f, det.centerXNorm * w, 6.0f)
        assertEquals(176f, det.centerYNorm * h, 4.0f)
    }

    @Test
    fun obstructedClutteredIndoorScene_suppressesFalseBoxesInAutoMode() {
        val w = 320
        val h = 480
        // High-contrast checkerboard mimicking cluttered indoor room / dense tree branches
        val yData = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (((x / 10) + (y / 10)) % 2 == 0) 210 else 30
        }
        val detector = SkyAircraftDetector()
        val hint = SkyAircraftDetector.AircraftRoiHint(
            icao24 = "c11111",
            predictedXNorm = 0.5f,
            predictedYNorm = 0.5f,
            roiHalfWidthNorm = 0.15f,
            roiHalfHeightNorm = 0.15f,
            velocityXNormPerSec = 0f,
            velocityYNormPerSec = 0f,
            expectedSizeNorm = 0.04f,
            elevationDeg = 30.0
        )

        val result = detector.analyzeFrame(
            frame = SyntheticFrame(w, h, yData, uVal = 128, vVal = 128),
            hints = listOf(hint),
            visionMode = VisionMode.AUTO,
            timestampMs = 2000L
        )

        assertEquals(DetectedSkyMode.OBSTRUCTED, result.stats.activeMode)
        assertTrue("Expected no false detections in obstructed scene", result.detections.isEmpty())
    }

    @Test
    fun daySkyWithTreeBranchesAtBottomOfRoi_rejectsTreeTwigs() {
        val w = 320
        val h = 480
        // Bright blue sky above y=260, dark tree canopy + protruding twig below y=245
        val yData = IntArray(w * h) { idx ->
            val y = idx / w
            if (y < 260) 185 else 35
        }
        // Protruding tree twig sticking up into the sky just above the canopy (y=246..259)
        for (y in 246..259) {
            for (x in 156..164) {
                yData[y * w + x] = 35
            }
        }
        // Isolated leaf cluster right above the dark canopy (y=238..242)
        for (y in 238..242) {
            for (x in 158..162) {
                yData[y * w + x] = 40
            }
        }

        val detector = SkyAircraftDetector()
        val hint = SkyAircraftDetector.AircraftRoiHint(
            icao24 = "d77777",
            predictedXNorm = 160f / w,
            predictedYNorm = 230f / h,
            roiHalfWidthNorm = 48f / w,
            roiHalfHeightNorm = 48f / h,
            velocityXNormPerSec = 0f,
            velocityYNormPerSec = 0f,
            expectedSizeNorm = 10f / w,
            elevationDeg = 12.0
        )

        val result = detector.analyzeFrame(
            frame = SyntheticFrame(w, h, yData, uVal = 148, vVal = 112),
            hints = listOf(hint),
            visionMode = VisionMode.AUTO,
            timestampMs = 3000L
        )

        assertEquals(DetectedSkyMode.DAY, result.stats.activeMode)
        assertTrue(
            "Tree twigs/leaves near dark canopy should not produce a false aircraft lock",
            result.detections["d77777"] == null
        )
    }
}
