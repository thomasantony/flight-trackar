package com.flighttrackar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flighttrackar.math.CameraProjection
import com.flighttrackar.model.AdsbProviderChoice
import com.flighttrackar.model.DetectedSkyMode
import com.flighttrackar.model.VisionMode
import com.flighttrackar.ui.theme.HudColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun FlightArScreen(
    viewModel: FlightArViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = modifier.fillMaxSize().background(HudColors.DeepNightBg)) {
        // 1. Live CameraX Preview + Real-Time YUV Sky CV Pipeline
        CameraPreviewContainer(
            viewModel = viewModel,
            requestedZoomRatio = uiState.requestedZoomRatio,
            modifier = Modifier.fillMaxSize()
        )

        // 2. AR Flight Reticles, Visual Bounding Boxes & Optional Debug Tracker Layer
        ArFlightOverlay(
            uiState = uiState,
            modifier = Modifier.fillMaxSize()
        )

        // 3. Top Aviation HUD Bar (Compass Heading, Pitch, Source Status, Quick Toggles)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            TopTelemetryBar(
                uiState = uiState,
                onCycleVision = { viewModel.cycleVisionMode() },
                onToggleDebug = { viewModel.toggleDebugHud() },
                onToggleCalibrate = { viewModel.toggleCalibrationPanel() },
                onOpenSettings = { viewModel.setSettingsSheetVisible(true) }
            )

            // Feed error or waiting-for-GPS notice banner
            if (uiState.observer == null) {
                Spacer(modifier = Modifier.height(6.dp))
                StatusNoticeBanner(
                    text = "Acquiring GPS fix... (or tap Settings → Demo Simulation to test indoors)",
                    accent = HudColors.AmberNight
                )
            } else if (uiState.feedError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                StatusNoticeBanner(
                    text = "Feed notice: ${uiState.feedError}",
                    accent = HudColors.CoralRawAdsb
                )
            }

            // Debug / Tracker HUD Telemetry Strip
            if (uiState.settings.showDebugHud) {
                Spacer(modifier = Modifier.height(6.dp))
                DebugHudTelemetryStrip(
                    uiState = uiState,
                    onLeadChange = { viewModel.updateExtraPredictionLeadSec(it) }
                )
            }
        }

        // 4. Bottom Controls: Calibration Card (when open) + Dynamic Zoom Preset Bar
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (uiState.showCalibrationPanel) {
                CalibrationControlsCard(
                    calibration = uiState.settings.calibration,
                    bestVisualCandidate = uiState.bestCalibrationCandidate,
                    onNudge = { dAz, dPitch -> viewModel.nudgeCalibration(dAz, dPitch) },
                    onReset = { viewModel.resetCalibration() },
                    onSnapToVisualLock = { viewModel.snapCalibrationToVisualLock() },
                    onAutoTrimChange = { viewModel.setAutoTrimFromVisualLock(it) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            ZoomPresetBar(
                currentZoom = uiState.intrinsics.zoomRatio,
                minZoom = uiState.intrinsics.minZoomRatio,
                maxZoom = uiState.intrinsics.maxZoomRatio,
                onSelectZoom = { viewModel.requestZoomRatio(it) }
            )
        }

        // 5. Flight Details Modal Sheet (tap any aircraft)
        uiState.selectedAircraft?.let { selected ->
            FlightDetailSheet(
                aircraft = selected,
                units = uiState.settings.unitSystem,
                onDismiss = { viewModel.selectAircraft(null) }
            )
        }

        // 6. Settings / ADS-B Source Modal Sheet
        if (uiState.showSettingsSheet) {
            SettingsSheet(
                settings = uiState.settings,
                onProviderChange = { viewModel.updateProvider(it) },
                onVisionModeChange = { viewModel.updateVisionMode(it) },
                onUnitSystemChange = { viewModel.updateUnitSystem(it) },
                onRadiusChange = { viewModel.updateSearchRadiusNm(it) },
                onExtraLeadChange = { viewModel.updateExtraPredictionLeadSec(it) },
                onDismiss = { viewModel.setSettingsSheetVisible(false) }
            )
        }
    }
}

@Composable
private fun TopTelemetryBar(
    uiState: FlightArUiState,
    onCycleVision: () -> Unit,
    onToggleDebug: () -> Unit,
    onToggleCalibrate: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HudColors.PanelSurface)
            .border(1.dp, HudColors.PanelBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Heading, Pitch & Track Count
        Column {
            val hdg = uiState.trueHeadingDeg.roundToInt() % 360
            val cardinal = cardinalDirection(hdg)
            val pitch = uiState.calibratedPitchDeg.roundToInt()
            Text(
                text = String.format(Locale.US, "HDG %03d° %s  PIT %+d°", hdg, cardinal, pitch),
                color = HudColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            val visibleCount = uiState.projectedAircraft.count { it.isInViewport }
            val lockedCount = uiState.projectedAircraft.count { it.isInViewport && it.visualDetection != null }
            val sourceTag = if (uiState.settings.provider == AdsbProviderChoice.DEMO_SIM) "DEMO" else uiState.activeSourceName
            Text(
                text = "$sourceTag · $visibleCount in view / ${uiState.totalTrackedCount} near" +
                    if (lockedCount > 0) " · $lockedCount locked" else "",
                color = if (lockedCount > 0) HudColors.EmeraldLock else HudColors.TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Quick-Toggle HUD Buttons
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val (visionIcon, visionColor) = when (uiState.settings.visionMode) {
                VisionMode.OFF -> Icons.Default.VisibilityOff to HudColors.TextSecondary
                VisionMode.NIGHT -> Icons.Default.NightsStay to HudColors.AmberNight
                VisionMode.DAY -> Icons.Default.WbSunny to HudColors.CyanPrimary
                VisionMode.AUTO -> when (uiState.skyStats.activeMode) {
                    DetectedSkyMode.NIGHT -> Icons.Default.NightsStay to HudColors.AmberNight
                    DetectedSkyMode.DAY -> Icons.Default.WbSunny to HudColors.EmeraldLock
                    DetectedSkyMode.OBSTRUCTED -> Icons.Default.WbSunny to HudColors.TextSecondary
                }
            }
            HudIconPill(
                icon = visionIcon,
                label = uiState.settings.visionMode.label.take(3).uppercase(),
                active = uiState.settings.visionMode != VisionMode.OFF,
                tint = visionColor,
                onClick = onCycleVision
            )
            HudIconPill(
                icon = Icons.Default.Explore,
                label = "CAL",
                active = uiState.showCalibrationPanel ||
                    abs(uiState.settings.calibration.azimuthOffsetDeg) > 0.1f ||
                    abs(uiState.settings.calibration.pitchOffsetDeg) > 0.1f,
                tint = HudColors.CyanPrimary,
                onClick = onToggleCalibrate
            )
            HudIconPill(
                icon = Icons.Default.BugReport,
                label = "DBG",
                active = uiState.settings.showDebugHud,
                tint = HudColors.AmberNight,
                onClick = onToggleDebug
            )
            HudIconPill(
                icon = Icons.Default.Settings,
                label = null,
                active = false,
                tint = HudColors.TextPrimary,
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun HudIconPill(
    icon: ImageVector,
    label: String?,
    active: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) tint.copy(alpha = 0.22f) else Color(0x33FFFFFF))
            .border(
                width = 1.dp,
                color = if (active) tint else Color(0x22FFFFFF),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) tint else HudColors.TextPrimary,
            modifier = Modifier.size(16.dp)
        )
        if (label != null) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) tint else HudColors.TextPrimary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun DebugHudTelemetryStrip(
    uiState: FlightArUiState,
    onLeadChange: (Float) -> Unit
) {
    val (hFov, vFov) = CameraProjection.effectiveFovDeg(uiState.intrinsics)
    val sky = uiState.skyStats
    val cal = uiState.settings.calibration

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC09111F))
            .border(1.dp, HudColors.AmberNight.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = String.format(
                Locale.US,
                "CV:%s sky:%.0f%% Y:%.0f %.1fms (%.0ffps)  FOV:%.1f°×%.1f° @%.1fx",
                sky.activeMode.name,
                sky.skyConfidence * 100f,
                sky.meanLuminance,
                sky.processingTimeMs,
                sky.analyzedFps,
                hFov,
                vFov,
                uiState.intrinsics.zoomRatio
            ),
            color = HudColors.AmberNight,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = String.format(
                Locale.US,
                "CAL: az%+.1f° pit%+.1f°  DECL:%+.1f°  FEED:%dms  [○ Raw -> ◆ EKF -> ··· CTRV]",
                cal.azimuthOffsetDeg,
                cal.pitchOffsetDeg,
                uiState.observer?.magneticDeclinationDeg ?: 0f,
                uiState.lastFetchLatencyMs
            ),
            color = HudColors.TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = String.format(Locale.US, "LEAD +%.1fs", uiState.settings.extraPredictionLeadSec),
                color = HudColors.CyanPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
                value = uiState.settings.extraPredictionLeadSec,
                onValueChange = onLeadChange,
                valueRange = 0f..15f,
                modifier = Modifier.weight(1f).height(26.dp)
            )
        }
    }
}

@Composable
private fun ZoomPresetBar(
    currentZoom: Float,
    minZoom: Float,
    maxZoom: Float,
    onSelectZoom: (Float) -> Unit
) {
    // Build adaptive zoom stops tailored to the device (e.g. Pixel 9 Pro supports 0.5x, 1x, 2x, 5x, 10x!)
    val candidates = listOf(0.5f, 1.0f, 2.0f, 5.0f, 10.0f)
    val stops = candidates.filter { it >= minZoom - 0.05f && it <= maxZoom + 0.05f }
        .ifEmpty { listOf(1.0f) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(HudColors.PanelSurface)
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        for (stop in stops) {
            val isSelected = abs(currentZoom - stop) < (stop * 0.18f).coerceAtLeast(0.15f)
            val label = if (stop < 1f) String.format(Locale.US, ".%dx", (stop * 10).roundToInt())
            else "${stop.roundToInt()}x"

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) HudColors.CyanPrimary else Color(0x26FFFFFF))
                    .clickable { onSelectZoom(stop.coerceIn(minZoom, maxZoom)) }
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color(0xFF041422) else HudColors.TextPrimary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Text(
            text = String.format(Locale.US, "%.1fx", currentZoom),
            fontSize = 11.sp,
            color = HudColors.TextSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun StatusNoticeBanner(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xD90D1525))
            .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text = text, color = accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun cardinalDirection(deg: Int): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((deg + 22) % 360) / 45]
}
