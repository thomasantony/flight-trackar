package com.flighttrackar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flighttrackar.math.GeoMath
import com.flighttrackar.model.DetectedSkyMode
import com.flighttrackar.model.ProjectedAircraft
import com.flighttrackar.model.UnitSystem
import com.flighttrackar.ui.theme.HudColors
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDetailSheet(
    aircraft: ProjectedAircraft,
    units: UnitSystem,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val track = aircraft.track
    val accent = altitudeColor(track.altitudeMeters)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0D1525),
        contentColor = HudColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Flight,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = track.displayLabel,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        track.aircraftType?.let { type ->
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = type,
                                color = accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(accent.copy(alpha = 0.16f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    val subtitle = listOfNotNull(
                        track.description,
                        track.registration?.let { "Reg: $it" },
                        "ICAO: ${track.icao24.uppercase()}"
                    ).joinToString(" · ")
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = HudColors.TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0x22FFFFFF))
            Spacer(modifier = Modifier.height(14.dp))

            // Primary Flight Telemetry Grid
            val altPrimary = when (units) {
                UnitSystem.AVIATION -> "${(track.altitudeMeters * GeoMath.METERS_TO_FEET).roundToInt()} ft"
                UnitSystem.METRIC -> "${track.altitudeMeters.roundToInt()} m"
            }
            val speedStr = when (units) {
                UnitSystem.AVIATION -> "${(track.groundSpeedMps * GeoMath.MPS_TO_KNOTS).roundToInt()} kt"
                UnitSystem.METRIC -> "${(track.groundSpeedMps * GeoMath.MPS_TO_KMH).roundToInt()} km/h"
            }
            val vertRateStr = when (units) {
                UnitSystem.AVIATION -> String.format(Locale.US, "%+d fpm", (track.verticalRateMps * GeoMath.MPS_TO_FPM).roundToInt())
                UnitSystem.METRIC -> String.format(Locale.US, "%+.1f m/s", track.verticalRateMps)
            }
            val distStr = when (units) {
                UnitSystem.AVIATION -> String.format(Locale.US, "%.1f nm", aircraft.slantRangeMeters * GeoMath.METERS_TO_NM)
                UnitSystem.METRIC -> String.format(Locale.US, "%.1f km", aircraft.slantRangeMeters / 1000.0)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MetricTile("ALTITUDE", altPrimary, Modifier.weight(1f), accent)
                MetricTile("GROUND SPD", speedStr, Modifier.weight(1f))
                MetricTile("VERT RATE", vertRateStr, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MetricTile("SLANT DIST", distStr, Modifier.weight(1f))
                MetricTile(
                    "TRACK",
                    String.format(Locale.US, "%03d° T", track.trackDeg.roundToInt() % 360),
                    Modifier.weight(1f)
                )
                val turnSrc = if (track.hasTelemetryTurnRate) "ADS-B" else "EKF"
                MetricTile(
                    "TURN ($turnSrc)",
                    String.format(Locale.US, "%+.2f°/s", track.turnRateDegPerSec),
                    Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Observer Relative Geometry & Tracker / Vision Diagnostics
            DetailRow(
                "Bearing / Elevation",
                String.format(Locale.US, "%03d° True  /  %+.1f° above horizon", aircraft.azimuthDeg.roundToInt() % 360, aircraft.elevationDeg)
            )
            val baroGeo = buildString {
                track.baroAltitudeMeters?.let {
                    append("Baro ${(it * GeoMath.METERS_TO_FEET).roundToInt()} ft")
                }
                track.geomAltitudeMeters?.let {
                    if (isNotEmpty()) append(" · ")
                    append("Geom ${(it * GeoMath.METERS_TO_FEET).roundToInt()} ft")
                }
            }.ifEmpty { "—" }
            DetailRow("Altitude Sources", baroGeo)
            DetailRow("Squawk", track.squawk ?: "—")
            DetailRow(
                "ADS-B Feed & EKF",
                String.format(
                    Locale.US,
                    "%s · age %.1fs · lead +%.1fs · σ≈%dm",
                    track.sourceName,
                    track.dataAgeSec,
                    track.totalPredictionLeadSec,
                    track.positionSigmaMeters.roundToInt()
                )
            )

            val det = aircraft.visualDetection
            val visionSummary = if (det != null) {
                val modeStr = when {
                    det.strobeDetected -> "Night (Strobe Detected)"
                    det.modeUsed == DetectedSkyMode.NIGHT -> "Night (Nav Light)"
                    else -> "Day (Sky Silhouette)"
                }
                String.format(Locale.US, "LOCKED · %s · %d%% (SNR %.1f)", modeStr, (det.confidence * 100).roundToInt(), det.snr)
            } else {
                "Not visually locked in camera frame"
            }
            DetailRow(
                "Camera Vision",
                visionSummary,
                valueColor = if (det != null) HudColors.EmeraldLock else HudColors.TextSecondary
            )
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = HudColors.TextPrimary
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF152038))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = HudColors.TextSecondary,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = HudColors.TextPrimary
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = HudColors.TextSecondary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            color = valueColor,
            fontFamily = FontFamily.Monospace
        )
    }
}
