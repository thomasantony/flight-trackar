package com.flighttrackar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flighttrackar.model.CalibrationOffsets
import com.flighttrackar.model.ProjectedAircraft
import com.flighttrackar.ui.theme.HudColors
import java.util.Locale

/**
 * Floating calibration overlay for correcting magnetometer compass drift and pitch bias,
 * with manual nudge steps and one-tap Visual Lock-On Sync.
 */
@Composable
fun CalibrationControlsCard(
    calibration: CalibrationOffsets,
    bestVisualCandidate: ProjectedAircraft?,
    onNudge: (deltaAzDeg: Float, deltaPitchDeg: Float) -> Unit,
    onReset: () -> Unit,
    onSnapToVisualLock: () -> Unit,
    onAutoTrimChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HudColors.PanelSurface)
            .border(1.dp, HudColors.PanelBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "COMPASS & PITCH CALIBRATION",
                color = HudColors.CyanPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            OutlinedButton(
                onClick = onReset,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("Reset", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Azimuth Nudge Row
        NudgeRow(
            label = String.format(Locale.US, "AZIMUTH  %+5.1f°", calibration.azimuthOffsetDeg),
            onMinusBig = { onNudge(-2.0f, 0f) },
            onMinusSmall = { onNudge(-0.5f, 0f) },
            onPlusSmall = { onNudge(+0.5f, 0f) },
            onPlusBig = { onNudge(+2.0f, 0f) }
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Pitch Nudge Row
        NudgeRow(
            label = String.format(Locale.US, "PITCH    %+5.1f°", calibration.pitchOffsetDeg),
            onMinusBig = { onNudge(0f, -2.0f) },
            onMinusSmall = { onNudge(0f, -0.5f) },
            onPlusSmall = { onNudge(0f, +0.5f) },
            onPlusBig = { onNudge(0f, +2.0f) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Visual Lock-On Sync Button + Auto-Trim Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onSnapToVisualLock,
                enabled = bestVisualCandidate != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HudColors.EmeraldLock,
                    contentColor = Color(0xFF031F14),
                    disabledContainerColor = Color(0xFF1E293B),
                    disabledContentColor = HudColors.TextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                val label = if (bestVisualCandidate != null) {
                    "Lock-On Sync (${bestVisualCandidate.track.displayLabel})"
                } else {
                    "Lock-On Sync (No Visual Lock)"
                }
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Auto",
                    fontSize = 11.sp,
                    color = HudColors.TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = calibration.autoTrimFromVisualLock,
                    onCheckedChange = onAutoTrimChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = HudColors.EmeraldLock)
                )
            }
        }
    }
}

@Composable
private fun NudgeRow(
    label: String,
    onMinusBig: () -> Unit,
    onMinusSmall: () -> Unit,
    onPlusSmall: () -> Unit,
    onPlusBig: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            color = HudColors.TextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NudgeChip("-2°", onMinusBig)
            NudgeChip("-0.5°", onMinusSmall)
            NudgeChip("+0.5°", onPlusSmall)
            NudgeChip("+2°", onPlusBig)
        }
    }
}

@Composable
private fun NudgeChip(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
