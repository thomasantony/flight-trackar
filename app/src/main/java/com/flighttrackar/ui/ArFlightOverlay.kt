package com.flighttrackar.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.flighttrackar.math.GeoMath
import com.flighttrackar.model.DetectedSkyMode
import com.flighttrackar.model.ProjectedAircraft
import com.flighttrackar.model.UnitSystem
import com.flighttrackar.ui.theme.HudColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders the full AR flight overlay:
 *  - Subtle center boresight crosshair
 *  - EKF-predicted aircraft diamonds & callsign/altitude/speed/distance tags
 *  - Camera CV visual aircraft bounding boxes (corner brackets) with Day/Night/Strobe badge
 *  - Debug/Tracker HUD layer: raw ADS-B point -> EKF compensation vector, future CTRV arc,
 *    CV search ROI boxes, data age, and turn rate
 */
@Composable
fun ArFlightOverlay(
    uiState: FlightArUiState,
    modifier: Modifier = Modifier
) {
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }
    val subLabelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(230, 203, 213, 225)
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
    }
    val badgePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 23f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }

    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) }
    val roiDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val vw = size.width
        val vh = size.height

        // Subtle center boresight crosshair for aiming & calibration
        drawCenterCrosshair(vw / 2f, vh / 2f)

        val showDebug = uiState.settings.showDebugHud
        val units = uiState.settings.unitSystem
        val selectedId = uiState.selectedIcao24

        // Draw far aircraft first so closest/selected aircraft paint cleanly on top
        val visible = uiState.projectedAircraft
            .filter { it.isInViewport }
            .asReversed()

        for (ac in visible) {
            val isSelected = ac.track.icao24 == selectedId
            val altColor = altitudeColor(ac.track.altitudeMeters)
            val predOffset = Offset(ac.predictedScreenX, ac.predictedScreenY)

            // 1. Debug HUD: Raw delayed ADS-B -> EKF predicted vector + future CTRV curve + CV ROI
            if (showDebug) {
                if (ac.rawScreenX != null && ac.rawScreenY != null) {
                    val rawOffset = Offset(ac.rawScreenX, ac.rawScreenY)
                    drawLine(
                        color = HudColors.CoralRawAdsb.copy(alpha = 0.85f),
                        start = rawOffset,
                        end = predOffset,
                        strokeWidth = 2.5f,
                        pathEffect = dashEffect
                    )
                    drawCircle(
                        color = HudColors.CoralRawAdsb,
                        radius = 7f,
                        center = rawOffset,
                        style = Stroke(width = 2.5f)
                    )
                }

                // Future CTRV trajectory (+3s, +6s, +9s)
                var prev = predOffset
                ac.futureScreenPoints.forEachIndexed { idx, (fx, fy) ->
                    val next = Offset(fx, fy)
                    val alpha = (0.75f - idx * 0.2f).coerceAtLeast(0.2f)
                    drawLine(
                        color = HudColors.CyanPrimary.copy(alpha = alpha),
                        start = prev,
                        end = next,
                        strokeWidth = 2.2f
                    )
                    drawCircle(
                        color = HudColors.CyanPrimary.copy(alpha = alpha),
                        radius = 4f,
                        center = next
                    )
                    prev = next
                }

                // Search ROI box from visual detector
                val det = ac.visualDetection
                if (det != null && det.roiRightNorm > det.roiLeftNorm) {
                    val rx = det.roiLeftNorm * vw
                    val ry = det.roiTopNorm * vh
                    val rw = (det.roiRightNorm - det.roiLeftNorm) * vw
                    val rh = (det.roiBottomNorm - det.roiTopNorm) * vh
                    drawRect(
                        color = HudColors.AmberNight.copy(alpha = 0.45f),
                        topLeft = Offset(rx, ry),
                        size = Size(rw, rh),
                        style = Stroke(width = 1.8f, pathEffect = roiDashEffect)
                    )
                }
            }

            // 2. Visual Detection Bounding Box (from Day/Night camera CV pipeline)
            val det = ac.visualDetection
            if (det != null && det.confidence >= 0.55f) {
                val boxCx = det.centerXNorm * vw
                val boxCy = det.centerYNorm * vh
                val boxW = (det.boxWidthNorm * vw).coerceIn(52f, 260f)
                val boxH = (det.boxHeightNorm * vh).coerceIn(52f, 260f)
                val boxColor = when {
                    det.strobeDetected -> HudColors.AmberNight
                    det.modeUsed == DetectedSkyMode.NIGHT -> Color(0xFFFB923C)
                    else -> HudColors.EmeraldLock
                }

                // Thin tether linking ADS-B predicted reticle to visually locked aircraft box
                drawLine(
                    color = boxColor.copy(alpha = 0.7f),
                    start = predOffset,
                    end = Offset(boxCx, boxCy),
                    strokeWidth = 2f,
                    pathEffect = dashEffect
                )

                drawCornerBracketBox(
                    left = boxCx - boxW / 2f,
                    top = boxCy - boxH / 2f,
                    width = boxW,
                    height = boxH,
                    color = boxColor,
                    strokeWidth = if (isSelected) 5f else 3.5f
                )

                val modeTag = when {
                    det.strobeDetected -> "STROBE ${(det.confidence * 100).roundToInt()}%"
                    det.modeUsed == DetectedSkyMode.NIGHT -> "NIGHT ${(det.confidence * 100).roundToInt()}%"
                    else -> "LOCK ${(det.confidence * 100).roundToInt()}%"
                }
                badgePaint.color = boxColor.toArgbInt()
                drawContext.canvas.nativeCanvas.drawText(
                    modeTag,
                    boxCx - boxW / 2f,
                    (boxCy - boxH / 2f - 10f).coerceAtLeast(30f),
                    badgePaint
                )
            }

            // 3. EKF-Predicted Aircraft Diamond Reticle
            val reticleRadius = if (isSelected) 20f else 14f
            drawDiamond(
                center = predOffset,
                radius = reticleRadius,
                color = if (isSelected) Color.White else altColor,
                strokeWidth = if (isSelected) 4f else 2.8f
            )
            drawCircle(
                color = altColor,
                radius = 3.5f,
                center = predOffset
            )

            // 4. Compact Aviation Data Tag Card
            val title = buildString {
                append(ac.track.displayLabel)
                ac.track.aircraftType?.let { append(" · ").append(it) }
            }
            val subLine = formatCompactTelemetry(ac, units)
            val debugLine = if (showDebug) {
                String.format(
                    Locale.US,
                    "age:%.1fs lead:+%.1fs ω:%+.1f°/s",
                    ac.track.dataAgeSec,
                    ac.track.totalPredictionLeadSec,
                    ac.track.turnRateDegPerSec
                )
            } else null

            val titleW = labelPaint.measureText(title)
            val subW = subLabelPaint.measureText(subLine)
            val dbgW = debugLine?.let { badgePaint.measureText(it) } ?: 0f
            val cardW = maxOf(titleW, subW, dbgW) + 26f
            val cardH = if (debugLine != null) 104f else 76f

            // Place card cleanly to upper-right of reticle (flip left if near right screen edge)
            val rawCardX = predOffset.x + 22f
            val cardX = if (rawCardX + cardW > vw - 12f) {
                (predOffset.x - 22f - cardW).coerceAtLeast(10f)
            } else rawCardX
            val cardY = (predOffset.y - cardH - 12f).coerceIn(130f, vh - cardH - 120f)

            // Leader stem from reticle to tag card
            val stemEndX = if (cardX > predOffset.x) cardX else cardX + cardW
            drawLine(
                color = altColor.copy(alpha = 0.7f),
                start = predOffset,
                end = Offset(stemEndX, cardY + cardH * 0.7f),
                strokeWidth = 2f
            )

            // Dark translucent card background + accent border
            drawRoundRect(
                color = Color(0xBF08101E),
                topLeft = Offset(cardX, cardY),
                size = Size(cardW, cardH),
                cornerRadius = CornerRadius(12f, 12f)
            )
            drawRoundRect(
                color = if (isSelected) Color.White else altColor.copy(alpha = 0.75f),
                topLeft = Offset(cardX, cardY),
                size = Size(cardW, cardH),
                cornerRadius = CornerRadius(12f, 12f),
                style = Stroke(width = if (isSelected) 3f else 1.8f)
            )

            val nc = drawContext.canvas.nativeCanvas
            nc.drawText(title, cardX + 13f, cardY + 33f, labelPaint)
            nc.drawText(subLine, cardX + 13f, cardY + 62f, subLabelPaint)
            if (debugLine != null) {
                badgePaint.color = HudColors.CyanPrimary.toArgbInt()
                nc.drawText(debugLine, cardX + 13f, cardY + 90f, badgePaint)
            }
        }
    }
}

private fun DrawScope.drawCenterCrosshair(cx: Float, cy: Float) {
    val gap = 14f
    val arm = 28f
    val color = Color.White.copy(alpha = 0.35f)
    drawLine(color, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = 2f)
    drawLine(color, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = 2f)
    drawLine(color, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = 2f)
    drawLine(color, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = 2f)
    drawCircle(color, radius = 2.5f, center = Offset(cx, cy))
}

private fun DrawScope.drawDiamond(center: Offset, radius: Float, color: Color, strokeWidth: Float) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius, center.y)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius, center.y)
        close()
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidth))
}

private fun DrawScope.drawCornerBracketBox(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    strokeWidth: Float
) {
    val right = left + width
    val bottom = top + height
    val c = (minOf(width, height) * 0.28f).coerceIn(12f, 34f)

    // Subtle translucent fill
    drawRect(
        color = color.copy(alpha = 0.10f),
        topLeft = Offset(left, top),
        size = Size(width, height)
    )

    // Top-left
    drawLine(color, Offset(left, top), Offset(left + c, top), strokeWidth, StrokeCap.Square)
    drawLine(color, Offset(left, top), Offset(left, top + c), strokeWidth, StrokeCap.Square)
    // Top-right
    drawLine(color, Offset(right - c, top), Offset(right, top), strokeWidth, StrokeCap.Square)
    drawLine(color, Offset(right, top), Offset(right, top + c), strokeWidth, StrokeCap.Square)
    // Bottom-left
    drawLine(color, Offset(left, bottom), Offset(left + c, bottom), strokeWidth, StrokeCap.Square)
    drawLine(color, Offset(left, bottom - c), Offset(left, bottom), strokeWidth, StrokeCap.Square)
    // Bottom-right
    drawLine(color, Offset(right - c, bottom), Offset(right, bottom), strokeWidth, StrokeCap.Square)
    drawLine(color, Offset(right, bottom - c), Offset(right, bottom), strokeWidth, StrokeCap.Square)
}

fun altitudeColor(altitudeMeters: Double): Color {
    val altFt = altitudeMeters * GeoMath.METERS_TO_FEET
    return when {
        altFt < 5000.0 -> HudColors.EmeraldLock
        altFt < 18000.0 -> HudColors.CyanPrimary
        altFt < 30000.0 -> HudColors.AmberNight
        else -> HudColors.IndigoHighAlt
    }
}

private fun formatCompactTelemetry(ac: ProjectedAircraft, units: UnitSystem): String {
    val vr = ac.track.verticalRateMps
    val climbArrow = when {
        vr > 1.5 -> "↑"
        vr < -1.5 -> "↓"
        else -> ""
    }
    return when (units) {
        UnitSystem.AVIATION -> {
            val altFt = (ac.track.altitudeMeters * GeoMath.METERS_TO_FEET).roundToInt()
            val altStr = if (altFt >= 18000) "FL${(altFt / 100)}" else "${(altFt / 100) * 100}ft"
            val spdKt = (ac.track.groundSpeedMps * GeoMath.MPS_TO_KNOTS).roundToInt()
            val distNm = ac.slantRangeMeters * GeoMath.METERS_TO_NM
            String.format(Locale.US, "%s%s  %dkt  %.1fnm", altStr, climbArrow, spdKt, distNm)
        }
        UnitSystem.METRIC -> {
            val altM = ac.track.altitudeMeters.roundToInt()
            val spdKmh = (ac.track.groundSpeedMps * GeoMath.MPS_TO_KMH).roundToInt()
            val distKm = ac.slantRangeMeters / 1000.0
            String.format(Locale.US, "%dm%s  %dkm/h  %.1fkm", altM, climbArrow, spdKmh, distKm)
        }
    }
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(),
    (red * 255).roundToInt(),
    (green * 255).roundToInt(),
    (blue * 255).roundToInt()
)
