package com.flighttrackar.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flighttrackar.BuildConfig
import com.flighttrackar.model.AdsbProviderChoice
import com.flighttrackar.model.AppSettings
import com.flighttrackar.model.UnitSystem
import com.flighttrackar.model.VisionMode
import com.flighttrackar.ui.theme.HudColors
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onProviderChange: (AdsbProviderChoice) -> Unit,
    onVisionModeChange: (VisionMode) -> Unit,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onRadiusChange: (Int) -> Unit,
    onExtraLeadChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0D1525),
        contentColor = HudColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "FLIGHTTRACKAR SETTINGS",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = HudColors.CyanPrimary,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(14.dp))
            SectionHeader("ADS-B Data Source")

            for (choice in AdsbProviderChoice.entries) {
                val selected = settings.provider == choice
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color(0xFF162642) else Color.Transparent)
                        .clickable { onProviderChange(choice) }
                        .padding(vertical = 6.dp, horizontal = 6.dp)
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onProviderChange(choice) }
                    )
                    Column {
                        Text(text = choice.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = choice.subtitle, fontSize = 11.sp, color = HudColors.TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0x22FFFFFF))
            Spacer(modifier = Modifier.height(12.dp))

            SectionHeader("Camera Vision Mode (Sky Bounding Box)")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (mode in VisionMode.entries) {
                    FilterChip(
                        selected = settings.visionMode == mode,
                        onClick = { onVisionModeChange(mode) },
                        label = { Text(mode.label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SectionHeader("Units")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (u in UnitSystem.entries) {
                    FilterChip(
                        selected = settings.unitSystem == u,
                        onClick = { onUnitSystemChange(u) },
                        label = { Text(u.label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            SectionHeader("Search Radius: ${settings.searchRadiusNm} nm (${(settings.searchRadiusNm * 1.852).roundToInt()} km)")
            Slider(
                value = settings.searchRadiusNm.toFloat(),
                onValueChange = { onRadiusChange(it.roundToInt()) },
                valueRange = 10f..100f,
                steps = 8
            )

            Spacer(modifier = Modifier.height(8.dp))
            val leadLabel = if (settings.extraPredictionLeadSec <= 0.1f) {
                "Live Now (compensates ADS-B delay automatically)"
            } else {
                String.format(Locale.US, "+%.1fs ahead of live position", settings.extraPredictionLeadSec)
            }
            SectionHeader("CTRV Future Prediction Lead: $leadLabel")
            Slider(
                value = settings.extraPredictionLeadSec,
                onValueChange = onExtraLeadChange,
                valueRange = 0f..15f
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0x22FFFFFF))
            Spacer(modifier = Modifier.height(12.dp))

            SectionHeader("About, Privacy & Open Data")
            Text(
                text = "FlightTrackAR v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "Camera frames are processed strictly on-device and never recorded or transmitted. " +
                    "Live flight positions are fetched from open community ADS-B networks (airplanes.live, adsb.lol, OpenSky Network).",
                fontSize = 11.sp,
                color = HudColors.TextSecondary,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        "https://thomasantony.github.io/flight-trackar/privacy-policy.html".toUri()
                    )
                    runCatching { context.startActivity(intent) }
                }
            ) {
                Text(
                    text = "View Privacy Policy",
                    color = HudColors.CyanPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = HudColors.TextSecondary,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}
