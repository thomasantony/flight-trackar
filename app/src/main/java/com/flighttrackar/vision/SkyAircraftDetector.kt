package com.flighttrackar.vision

import com.flighttrackar.model.DetectedSkyMode
import com.flighttrackar.model.SkyFrameStats
import com.flighttrackar.model.VisionMode
import com.flighttrackar.model.VisualDetection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin, real-time ADS-B-guided computer vision aircraft detector with dedicated
 * Day (sky contrast/silhouette blob) and Night (bright nav-light & blinking strobe tracker)
 * pipelines.
 *
 * Operates on an upright grayscale luma frame (and optional Cb/Cr chroma accessor) mapped
 * to normalized viewport coordinates [0..1], making it both fast on-device (CameraX YUV)
 * and 100% deterministic to unit test on synthetic sky images.
 */
class SkyAircraftDetector {

    /**
     * Lightweight abstraction over a luma/chroma frame already mapped to upright viewport space.
     */
    interface UprightFrame {
        val width: Int
        val height: Int
        /** Returns luma Y in [0..255] at upright viewport pixel (x, y). */
        fun getY(x: Int, y: Int): Int
        /** Returns chroma U (Cb, 0..255, neutral=128) or 128 if unavailable. */
        fun getU(x: Int, y: Int): Int = 128
        /** Returns chroma V (Cr, 0..255, neutral=128) or 128 if unavailable. */
        fun getV(x: Int, y: Int): Int = 128
    }

    /**
     * Hint for a single aircraft projected into the camera viewport.
     */
    data class AircraftRoiHint(
        val icao24: String,
        /** Normalized predicted screen center [0..1]. */
        val predictedXNorm: Float,
        val predictedYNorm: Float,
        /** Normalized search half-width & half-height based on compass + EKF uncertainty. */
        val roiHalfWidthNorm: Float,
        val roiHalfHeightNorm: Float,
        /** Predicted screen velocity (normalized units / second) for night-mode star rejection. */
        val velocityXNormPerSec: Float,
        val velocityYNormPerSec: Float,
        /** Expected apparent aircraft diameter in normalized viewport width. */
        val expectedSizeNorm: Float,
        /** Elevation above horizon in degrees (very low elevation is prone to ground clutter). */
        val elevationDeg: Double
    )

    data class FrameDetectionResult(
        val detections: Map<String, VisualDetection>,
        val stats: SkyFrameStats
    )

    private data class CandidateBlob(
        val cx: Float,
        val cy: Float,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val area: Int,
        val peakContrast: Float,
        val peakLuma: Float,
        val meanBorderDiff: Float,
        val redChromaBoost: Float
    )

    private data class TrackHistory(
        var smoothXNorm: Float,
        var smoothYNorm: Float,
        var smoothWNorm: Float,
        var smoothHNorm: Float,
        var confidence: Float,
        var mode: DetectedSkyMode,
        var missedFrames: Int,
        var lastTimestampMs: Long,
        /** Recent peak luminance samples (for night-mode strobe blink detection). */
        val lumaHistory: ArrayDeque<Pair<Long, Float>> = ArrayDeque(),
        /** Recent centroid positions (for night-mode motion consistency check). */
        val posHistory: ArrayDeque<Triple<Long, Float, Float>> = ArrayDeque(),
        var strobeDetected: Boolean = false
    )

    private val trackHistories = HashMap<String, TrackHistory>()
    private var lastAutoMode: DetectedSkyMode = DetectedSkyMode.DAY
    private var lastFrameTimeMs: Long = 0L
    private var smoothedFps: Float = 0f

    companion object {
        private const val NIGHT_ENTER_LUMA = 52f
        private const val NIGHT_EXIT_LUMA = 68f
        private const val MAX_MISSED_FRAMES = 4
        private const val STROBE_WINDOW_MS = 2200L
    }

    /**
     * Analyzes one camera frame against the current list of ADS-B-projected aircraft hints.
     */
    fun analyzeFrame(
        frame: UprightFrame,
        hints: List<AircraftRoiHint>,
        visionMode: VisionMode,
        timestampMs: Long = System.currentTimeMillis()
    ): FrameDetectionResult {
        val startNs = System.nanoTime()
        if (lastFrameTimeMs > 0L && timestampMs > lastFrameTimeMs) {
            val instFps = 1000f / (timestampMs - lastFrameTimeMs).coerceAtLeast(1L)
            smoothedFps = if (smoothedFps == 0f) instFps else smoothedFps * 0.85f + instFps * 0.15f
        }
        lastFrameTimeMs = timestampMs

        if (visionMode == VisionMode.OFF || frame.width < 32 || frame.height < 32) {
            trackHistories.clear()
            return FrameDetectionResult(
                detections = emptyMap(),
                stats = SkyFrameStats(
                    activeMode = DetectedSkyMode.OBSTRUCTED,
                    meanLuminance = 0f,
                    skyConfidence = 0f,
                    processingTimeMs = 0f,
                    analyzedFps = smoothedFps
                )
            )
        }

        // Step 1: Assess global sky luminance & texture clutter (focus on upper 70% of frame)
        val skyCheck = assessGlobalSky(frame, visionMode)

        val detections = HashMap<String, VisualDetection>()
        val activeIcaos = HashSet<String>(hints.size)

        if (skyCheck.activeMode != DetectedSkyMode.OBSTRUCTED) {
            for (hint in hints) {
                if (hint.elevationDeg < 2.5) continue // Skip horizon clutter
                activeIcaos.add(hint.icao24)
                val det = detectInRoi(frame, hint, skyCheck.activeMode, skyCheck.skyConfidence, timestampMs)
                if (det != null) {
                    detections[hint.icao24] = det
                }
            }
        }

        // Clean up expired track histories
        val iter = trackHistories.entries.iterator()
        while (iter.hasNext()) {
            val (icao, hist) = iter.next()
            if (icao !in activeIcaos || timestampMs - hist.lastTimestampMs > 4000L) {
                iter.remove()
            }
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f
        return FrameDetectionResult(
            detections = detections,
            stats = SkyFrameStats(
                activeMode = skyCheck.activeMode,
                meanLuminance = skyCheck.meanLuma,
                skyConfidence = skyCheck.skyConfidence,
                processingTimeMs = elapsedMs,
                analyzedFps = smoothedFps
            )
        )
    }

    private data class GlobalSkyAssessment(
        val activeMode: DetectedSkyMode,
        val meanLuma: Float,
        val skyConfidence: Float
    )

    /**
     * Evaluates whether sky is visible (vs indoor walls/dense foliage) and classifies Day vs Night
     * with hysteresis to avoid dusk flickering.
     */
    private fun assessGlobalSky(frame: UprightFrame, modePref: VisionMode): GlobalSkyAssessment {
        val w = frame.width
        val h = frame.height
        val stepX = max(4, w / 28)
        val stepY = max(4, h / 28)
        val maxY = (h * 0.72f).toInt().coerceAtLeast(stepY * 2)

        var sumY = 0L
        var sumBlueBias = 0L
        var edgeCount = 0
        var sampleCount = 0

        val localDelta = max(2, min(stepX, stepY) / 3)
        var y = stepY
        while (y < maxY) {
            var x = stepX
            while (x < w - stepX) {
                val c = frame.getY(x, y)
                val rCoarse = frame.getY(x + stepX, y)
                val dCoarse = frame.getY(x, y + stepY)
                val rFine = frame.getY(x + localDelta, y)
                val dFine = frame.getY(x, y + localDelta)
                sumY += c
                // Blue sky has U (Cb) > V (Cr)
                sumBlueBias += (frame.getU(x, y) - frame.getV(x, y))
                if (abs(c - rCoarse) > 22 || abs(c - dCoarse) > 22 ||
                    abs(c - rFine) > 18 || abs(c - dFine) > 18
                ) {
                    edgeCount++
                }
                sampleCount++
                x += stepX
            }
            y += stepY
        }

        if (sampleCount == 0) {
            return GlobalSkyAssessment(DetectedSkyMode.OBSTRUCTED, 0f, 0f)
        }

        val meanY = sumY.toFloat() / sampleCount
        val edgeRatio = edgeCount.toFloat() / sampleCount
        val blueBias = sumBlueBias.toFloat() / sampleCount

        // Smoothness score: open sky (day or night) has very low edge density;
        // trees/buildings/indoor rooms have high edge density (> 0.35).
        val smoothnessScore = (1f - (edgeRatio / 0.42f)).coerceIn(0f, 1f)

        val resolvedMode = when (modePref) {
            VisionMode.DAY -> DetectedSkyMode.DAY
            VisionMode.NIGHT -> DetectedSkyMode.NIGHT
            VisionMode.AUTO -> {
                val next = when (lastAutoMode) {
                    DetectedSkyMode.NIGHT -> if (meanY > NIGHT_EXIT_LUMA) DetectedSkyMode.DAY else DetectedSkyMode.NIGHT
                    else -> if (meanY < NIGHT_ENTER_LUMA) DetectedSkyMode.NIGHT else DetectedSkyMode.DAY
                }
                lastAutoMode = next
                next
            }
            VisionMode.OFF -> DetectedSkyMode.OBSTRUCTED
        }

        val skyConf = when (resolvedMode) {
            DetectedSkyMode.DAY -> {
                // Smoothness gates sky confidence multiplicatively so bright cluttered scenes
                // (indoor walls, dense foliage) cannot score as open sky.
                val brightScore = ((meanY - 45f) / 70f).coerceIn(0f, 1f)
                val blueBonus = if (blueBias > 4f) 0.12f else 0f
                (smoothnessScore * (0.55f + 0.45f * brightScore + blueBonus)).coerceIn(0f, 1f)
            }
            DetectedSkyMode.NIGHT -> {
                val darkScore = ((95f - meanY) / 70f).coerceIn(0f, 1f)
                (smoothnessScore * (0.55f + 0.45f * darkScore)).coerceIn(0f, 1f)
            }
            DetectedSkyMode.OBSTRUCTED -> 0f
        }

        // Block as obstructed in AUTO mode when scene clutter is too high to be open sky
        val finalMode = if (modePref == VisionMode.AUTO && skyConf < 0.30f) {
            DetectedSkyMode.OBSTRUCTED
        } else {
            resolvedMode
        }

        return GlobalSkyAssessment(finalMode, meanY, skyConf)
    }

    /**
     * Searches the ADS-B-guided ROI for the most likely aircraft blob (Day) or light/strobe (Night),
     * then updates the per-aircraft temporal tracker.
     */
    private fun detectInRoi(
        frame: UprightFrame,
        hint: AircraftRoiHint,
        mode: DetectedSkyMode,
        globalSkyConf: Float,
        timestampMs: Long
    ): VisualDetection? {
        val w = frame.width
        val h = frame.height

        val predX = (hint.predictedXNorm * w).toInt()
        val predY = (hint.predictedYNorm * h).toInt()
        val halfW = (hint.roiHalfWidthNorm * w).toInt().coerceIn(28, w / 3)
        val halfH = (hint.roiHalfHeightNorm * h).toInt().coerceIn(28, h / 3)

        val x0 = (predX - halfW).coerceIn(2, w - 3)
        val y0 = (predY - halfH).coerceIn(2, h - 3)
        val x1 = (predX + halfW).coerceIn(x0 + 8, w - 2)
        val y1 = (predY + halfH).coerceIn(y0 + 8, h - 2)
        val roiW = x1 - x0
        val roiH = y1 - y0
        if (roiW < 12 || roiH < 12) return null

        val roiLeftNorm = x0.toFloat() / w
        val roiTopNorm = y0.toFloat() / h
        val roiRightNorm = x1.toFloat() / w
        val roiBottomNorm = y1.toFloat() / h

        // Extract ROI luma into contiguous flat array for fast local filtering & BFS
        val roiY = IntArray(roiW * roiH)
        for (ry in 0 until roiH) {
            val rowOff = ry * roiW
            val fy = y0 + ry
            for (rx in 0 until roiW) {
                roiY[rowOff + rx] = frame.getY(x0 + rx, fy)
            }
        }

        // Compute ROI robust background median & MAD on a subsampled grid
        val (medianY, madY, clutterRatio) = computeRoiStats(roiY, roiW, roiH)
        if (clutterRatio > 0.36f) {
            // Too textured (tree branches, roofline, dense broken clouds) to safely lock a box
            return holdOrDropTrack(hint.icao24, roiLeftNorm, roiTopNorm, roiRightNorm, roiBottomNorm, timestampMs)
        }

        val candidates = when (mode) {
            DetectedSkyMode.DAY -> findDayCandidates(roiY, roiW, roiH, madY, hint.expectedSizeNorm * w)
            DetectedSkyMode.NIGHT -> findNightCandidates(frame, roiY, x0, y0, roiW, roiH, medianY, madY)
            DetectedSkyMode.OBSTRUCTED -> emptyList()
        }

        val prevHist = trackHistories[hint.icao24]
        val expectedPx = (hint.expectedSizeNorm * w).coerceIn(3f, 80f)
        val maxDist = hypot(roiW.toFloat(), roiH.toFloat()) * 0.55f

        var bestCandidate: CandidateBlob? = null
        var bestScore = 0f

        for (cand in candidates) {
            val gx = x0 + cand.cx
            val gy = y0 + cand.cy

            // Distance to predicted ADS-B point
            val distPred = hypot(gx - predX.toFloat(), gy - predY.toFloat())
            val predProxScore = (1f - (distPred / maxDist).coerceIn(0f, 1f))

            // Temporal proximity bonus if we already have a visual track
            val tempBonus = if (prevHist != null) {
                val prevX = prevHist.smoothXNorm * w
                val prevY = prevHist.smoothYNorm * h
                val dtSec = ((timestampMs - prevHist.lastTimestampMs) / 1000f).coerceIn(0f, 1f)
                val expX = prevX + hint.velocityXNormPerSec * w * dtSec
                val expY = prevY + hint.velocityYNormPerSec * h * dtSec
                val distTrack = hypot(gx - expX, gy - expY)
                (1f - (distTrack / (roiW * 0.35f).coerceAtLeast(18f)).coerceIn(0f, 1f)) * 0.45f
            } else 0f

            val snr = cand.peakContrast / max(madY, 3.0f)
            val snrScore = ((snr - 2.5f) / 8.0f).coerceIn(0f, 1.2f)

            val blobW = (cand.maxX - cand.minX + 1).toFloat()
            val blobH = (cand.maxY - cand.minY + 1).toFloat()
            val blobDim = max(blobW, blobH)

            // Size plausibility score
            val sizeRatio = blobDim / max(expectedPx, 4f)
            val sizeScore = if (sizeRatio in 0.25f..4.5f) 1.0f else 0.45f

            // Isolation score: sky ring around blob should be uniform (low border variance)
            val isoScore = (1f - (cand.meanBorderDiff / 32f)).coerceIn(0.15f, 1f)

            // Night-mode red nav-light / beacon chroma boost
            val chromaBonus = if (mode == DetectedSkyMode.NIGHT && cand.redChromaBoost > 10f) 0.20f else 0f

            val score = (predProxScore * 0.40f + snrScore * 0.35f + isoScore * 0.25f) * sizeScore + tempBonus + chromaBonus
            if (score > bestScore) {
                bestScore = score
                bestCandidate = cand
            }
        }

        val minAcceptScore = if (mode == DetectedSkyMode.NIGHT) 0.42f else 0.46f
        if (bestCandidate == null || bestScore < minAcceptScore) {
            return holdOrDropTrack(hint.icao24, roiLeftNorm, roiTopNorm, roiRightNorm, roiBottomNorm, timestampMs)
        }

        val gxNorm = (x0 + bestCandidate.cx) / w.toFloat()
        val gyNorm = (y0 + bestCandidate.cy) / h.toFloat()
        val padPx = max(8f, max(bestCandidate.maxX - bestCandidate.minX, bestCandidate.maxY - bestCandidate.minY) * 0.5f)
        val rawWNorm = ((bestCandidate.maxX - bestCandidate.minX + 1) + padPx * 2f).coerceIn(22f, roiW * 0.85f) / w.toFloat()
        val rawHNorm = ((bestCandidate.maxY - bestCandidate.minY + 1) + padPx * 2f).coerceIn(22f, roiH * 0.85f) / h.toFloat()

        // Smooth bounding box and update night-mode strobe / motion tracker
        val hist = prevHist ?: TrackHistory(
            smoothXNorm = gxNorm,
            smoothYNorm = gyNorm,
            smoothWNorm = rawWNorm,
            smoothHNorm = rawHNorm,
            confidence = 0.45f,
            mode = mode,
            missedFrames = 0,
            lastTimestampMs = timestampMs
        ).also { trackHistories[hint.icao24] = it }

        val alpha = 0.45f
        hist.smoothXNorm = hist.smoothXNorm * (1f - alpha) + gxNorm * alpha
        hist.smoothYNorm = hist.smoothYNorm * (1f - alpha) + gyNorm * alpha
        hist.smoothWNorm = hist.smoothWNorm * 0.7f + rawWNorm * 0.3f
        hist.smoothHNorm = hist.smoothHNorm * 0.7f + rawHNorm * 0.3f
        hist.missedFrames = 0
        hist.mode = mode
        hist.lastTimestampMs = timestampMs

        // Night-mode temporal strobe & motion analysis
        var nightBonus = 0f
        if (mode == DetectedSkyMode.NIGHT) {
            hist.lumaHistory.addLast(timestampMs to bestCandidate.peakLuma)
            while (hist.lumaHistory.isNotEmpty() && timestampMs - hist.lumaHistory.first().first > STROBE_WINDOW_MS) {
                hist.lumaHistory.removeFirst()
            }
            hist.posHistory.addLast(Triple(timestampMs, gxNorm, gyNorm))
            while (hist.posHistory.isNotEmpty() && timestampMs - hist.posHistory.first().first > STROBE_WINDOW_MS) {
                hist.posHistory.removeFirst()
            }

            if (hist.lumaHistory.size >= 5) {
                val lumas = hist.lumaHistory.map { it.second }
                val lumaSpan = (lumas.maxOrNull() ?: 0f) - (lumas.minOrNull() ?: 0f)
                // Aviation strobes cause distinct intensity peaks (> 28 luma swing)
                hist.strobeDetected = lumaSpan > 28f
                if (hist.strobeDetected) nightBonus += 0.18f
            }

            // Motion consistency bonus vs predicted velocity (helps distinguish planes from stars)
            if (hist.posHistory.size >= 4) {
                val first = hist.posHistory.first()
                val last = hist.posHistory.last()
                val dtSec = (last.first - first.first) / 1000f
                if (dtSec > 0.35f) {
                    val obsVx = (last.second - first.second) / dtSec
                    val obsVy = (last.third - first.third) / dtSec
                    val predSpeed = hypot(hint.velocityXNormPerSec, hint.velocityYNormPerSec)
                    val obsSpeed = hypot(obsVx, obsVy)
                    if (predSpeed > 0.004f && obsSpeed > 0.002f) {
                        val cosAlign = (obsVx * hint.velocityXNormPerSec + obsVy * hint.velocityYNormPerSec) /
                            (predSpeed * obsSpeed)
                        if (cosAlign > 0.5f) nightBonus += 0.15f
                    }
                }
            }
        } else {
            hist.strobeDetected = false
        }

        val frameConf = (bestScore * 0.72f + globalSkyConf * 0.18f + nightBonus).coerceIn(0f, 1f)
        hist.confidence = (hist.confidence * 0.6f + frameConf * 0.4f).coerceIn(0f, 1f)

        val snr = bestCandidate.peakContrast / max(madY, 3.0f)
        return VisualDetection(
            icao24 = hint.icao24,
            centerXNorm = hist.smoothXNorm,
            centerYNorm = hist.smoothYNorm,
            boxWidthNorm = hist.smoothWNorm,
            boxHeightNorm = hist.smoothHNorm,
            confidence = hist.confidence,
            modeUsed = mode,
            strobeDetected = hist.strobeDetected,
            snr = snr,
            roiLeftNorm = roiLeftNorm,
            roiTopNorm = roiTopNorm,
            roiRightNorm = roiRightNorm,
            roiBottomNorm = roiBottomNorm,
            candidateCount = candidates.size,
            timestampMs = timestampMs
        )
    }

    /**
     * Briefly coasts a locked visual bounding box through momentary strobe off-phases
     * or thin cloud wisps before dropping lock.
     */
    private fun holdOrDropTrack(
        icao24: String,
        roiL: Float,
        roiT: Float,
        roiR: Float,
        roiB: Float,
        timestampMs: Long
    ): VisualDetection? {
        val hist = trackHistories[icao24] ?: return null
        hist.missedFrames++
        hist.confidence *= 0.78f
        if (hist.missedFrames > MAX_MISSED_FRAMES || hist.confidence < 0.25f) {
            trackHistories.remove(icao24)
            return null
        }
        return VisualDetection(
            icao24 = icao24,
            centerXNorm = hist.smoothXNorm,
            centerYNorm = hist.smoothYNorm,
            boxWidthNorm = hist.smoothWNorm,
            boxHeightNorm = hist.smoothHNorm,
            confidence = hist.confidence,
            modeUsed = hist.mode,
            strobeDetected = hist.strobeDetected,
            snr = 0f,
            roiLeftNorm = roiL,
            roiTopNorm = roiT,
            roiRightNorm = roiR,
            roiBottomNorm = roiB,
            candidateCount = 0,
            timestampMs = timestampMs
        )
    }

    /**
     * Day-mode detector: estimates a smooth local sky background via a coarse 9x9-pixel
     * neighborhood ring (median/mean of surrounding ring at radius r=6), finds compact dark
     * silhouettes or bright sunlit fuselage glints above adaptive noise threshold, and extracts
     * connected blobs.
     */
    private fun findDayCandidates(
        roiY: IntArray,
        roiW: Int,
        roiH: Int,
        madY: Float,
        expectedPx: Float
    ): List<CandidateBlob> {
        val ringR = (expectedPx * 0.6f).toInt().coerceIn(5, 14)
        val darkThresh = max(14f, madY * 4.2f)
        val brightThresh = max(20f, madY * 5.2f)

        val mask = BooleanArray(roiW * roiH)
        val contrast = FloatArray(roiW * roiH)

        // Compare each pixel against 8-point sky ring at radius ringR (fast O(1) local background!)
        for (y in ringR until roiH - ringR) {
            val rowOff = y * roiW
            for (x in ringR until roiW - ringR) {
                val c = roiY[rowOff + x]
                val bg = (
                    roiY[(y - ringR) * roiW + x] +
                        roiY[(y + ringR) * roiW + x] +
                        roiY[rowOff + (x - ringR)] +
                        roiY[rowOff + (x + ringR)] +
                        roiY[(y - ringR) * roiW + (x - ringR)] +
                        roiY[(y - ringR) * roiW + (x + ringR)] +
                        roiY[(y + ringR) * roiW + (x - ringR)] +
                        roiY[(y + ringR) * roiW + (x + ringR)]
                    ) / 8f

                val darkDiff = bg - c
                val brightDiff = c - bg
                if (darkDiff > darkThresh) {
                    mask[rowOff + x] = true
                    contrast[rowOff + x] = darkDiff
                } else if (brightDiff > brightThresh) {
                    mask[rowOff + x] = true
                    contrast[rowOff + x] = brightDiff * 0.9f
                }
            }
        }

        val maxArea = ((roiW * roiH) * 0.08f).toInt().coerceIn(80, 900)
        return extractBlobs(
            roiY = roiY,
            mask = mask,
            contrast = contrast,
            roiW = roiW,
            roiH = roiH,
            minArea = 3,
            maxArea = maxArea,
            frame = null,
            x0 = 0,
            y0 = 0
        )
    }

    /**
     * Night-mode detector: locates compact bright point sources (nav lights, landing lights,
     * anti-collision strobes) above the dark sky floor, plus checks red chroma boost for beacons.
     */
    private fun findNightCandidates(
        frame: UprightFrame,
        roiY: IntArray,
        x0: Int,
        y0: Int,
        roiW: Int,
        roiH: Int,
        medianY: Float,
        madY: Float
    ): List<CandidateBlob> {
        val thresh = max(medianY + 26f, medianY + madY * 5.5f).coerceAtMost(235f)
        val mask = BooleanArray(roiW * roiH)
        val contrast = FloatArray(roiW * roiH)

        for (y in 2 until roiH - 2) {
            val rowOff = y * roiW
            for (x in 2 until roiW - 2) {
                val c = roiY[rowOff + x].toFloat()
                if (c > thresh) {
                    mask[rowOff + x] = true
                    contrast[rowOff + x] = c - medianY
                }
            }
        }

        return extractBlobs(
            roiY = roiY,
            mask = mask,
            contrast = contrast,
            roiW = roiW,
            roiH = roiH,
            minArea = 2,
            maxArea = 180,
            frame = frame,
            x0 = x0,
            y0 = y0
        )
    }

    /**
     * 4-connected component extraction with weighted centroid, bounding box,
     * surrounding-ring uniformity (isolation), and optional red-beacon chroma check.
     */
    private fun extractBlobs(
        roiY: IntArray,
        mask: BooleanArray,
        contrast: FloatArray,
        roiW: Int,
        roiH: Int,
        minArea: Int,
        maxArea: Int,
        frame: UprightFrame?,
        x0: Int,
        y0: Int
    ): List<CandidateBlob> {
        val visited = BooleanArray(roiW * roiH)
        val queue = IntArray(min(roiW * roiH, 2048))
        val results = ArrayList<CandidateBlob>(8)

        for (y in 2 until roiH - 2) {
            for (x in 2 until roiW - 2) {
                val idx = y * roiW + x
                if (!mask[idx] || visited[idx]) continue

                var qHead = 0
                var qTail = 0
                queue[qTail++] = idx
                visited[idx] = true

                var area = 0
                var sumWX = 0f
                var sumWY = 0f
                var sumW = 0f
                var peakContrast = 0f
                var peakLuma = 0f
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y

                while (qHead < qTail) {
                    val cur = queue[qHead++]
                    val cx = cur % roiW
                    val cy = cur / roiW
                    val wVal = contrast[cur].coerceAtLeast(1f)
                    val lumaVal = roiY[cur].toFloat()

                    area++
                    sumWX += cx * wVal
                    sumWY += cy * wVal
                    sumW += wVal
                    if (wVal > peakContrast) peakContrast = wVal
                    if (lumaVal > peakLuma) peakLuma = lumaVal
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    // Explore 4-neighbors
                    val neighbors = intArrayOf(cur - 1, cur + 1, cur - roiW, cur + roiW)
                    for (nb in neighbors) {
                        if (nb in 0 until (roiW * roiH) && mask[nb] && !visited[nb]) {
                            val nx = nb % roiW
                            val ny = nb / roiW
                            if (abs(nx - cx) + abs(ny - cy) == 1) {
                                visited[nb] = true
                                if (qTail < queue.size) {
                                    queue[qTail++] = nb
                                }
                            }
                        }
                    }
                }

                if (area in minArea..maxArea) {
                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1
                    // Reject huge thin scanlines or huge amorphous cloud edges
                    val aspect = max(bw, bh).toFloat() / max(1, min(bw, bh))
                    if (aspect > 9.0f && area > 20) continue

                    val cx = sumWX / sumW
                    val cy = sumWY / sumW

                    // Measure uniformity of a 1-pixel border ring 3 px outside the blob
                    val borderDiff = computeBorderUniformity(roiY, roiW, roiH, minX, minY, maxX, maxY)

                    // Night-mode red nav/beacon check (Cr/V > 128 indicates red)
                    val redBoost = if (frame != null) {
                        val fx = (x0 + cx.toInt()).coerceIn(0, frame.width - 1)
                        val fy = (y0 + cy.toInt()).coerceIn(0, frame.height - 1)
                        (frame.getV(fx, fy) - 128).toFloat()
                    } else 0f

                    results.add(
                        CandidateBlob(
                            cx = cx,
                            cy = cy,
                            minX = minX,
                            minY = minY,
                            maxX = maxX,
                            maxY = maxY,
                            area = area,
                            peakContrast = peakContrast,
                            peakLuma = peakLuma,
                            meanBorderDiff = borderDiff,
                            redChromaBoost = redBoost
                        )
                    )
                }
            }
        }
        return results
    }

    private fun computeBorderUniformity(
        roiY: IntArray,
        roiW: Int,
        roiH: Int,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int
    ): Float {
        val bx0 = (minX - 4).coerceAtLeast(0)
        val by0 = (minY - 4).coerceAtLeast(0)
        val bx1 = (maxX + 4).coerceAtMost(roiW - 1)
        val by1 = (maxY + 4).coerceAtMost(roiH - 1)

        var sum = 0
        var count = 0
        var x = bx0
        while (x <= bx1) {
            sum += roiY[by0 * roiW + x] + roiY[by1 * roiW + x]
            count += 2
            x += 2
        }
        var y = by0
        while (y <= by1) {
            sum += roiY[y * roiW + bx0] + roiY[y * roiW + bx1]
            count += 2
            y += 2
        }
        if (count == 0) return 0f
        val mean = sum.toFloat() / count

        var absDev = 0f
        x = bx0
        while (x <= bx1) {
            absDev += abs(roiY[by0 * roiW + x] - mean) + abs(roiY[by1 * roiW + x] - mean)
            x += 2
        }
        y = by0
        while (y <= by1) {
            absDev += abs(roiY[y * roiW + bx0] - mean) + abs(roiY[y * roiW + bx1] - mean)
            y += 2
        }
        return absDev / count
    }

    private fun computeRoiStats(roiY: IntArray, roiW: Int, roiH: Int): Triple<Float, Float, Float> {
        val step = max(2, min(roiW, roiH) / 20)
        val samples = IntArray(((roiW / step) + 1) * ((roiH / step) + 1))
        var n = 0
        var edges = 0

        var y = 0
        while (y < roiH - step) {
            var x = 0
            while (x < roiW - step) {
                val c = roiY[y * roiW + x]
                if (n < samples.size) samples[n++] = c
                if (abs(c - roiY[y * roiW + x + step]) > 20 || abs(c - roiY[(y + step) * roiW + x]) > 20) {
                    edges++
                }
                x += step
            }
            y += step
        }
        if (n == 0) return Triple(128f, 5f, 1f)

        val valid = samples.copyOf(n)
        valid.sort()
        val median = valid[n / 2].toFloat()

        val devs = IntArray(n) { i -> abs(valid[i] - median.toInt()) }
        devs.sort()
        val mad = (devs[n / 2] * 1.4826f).coerceAtLeast(2.5f)
        val clutter = edges.toFloat() / n
        return Triple(median, mad, clutter)
    }
}
