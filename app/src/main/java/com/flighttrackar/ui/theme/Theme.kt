package com.flighttrackar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object HudColors {
    val DeepNightBg = Color(0xFF060A12)
    val PanelSurface = Color(0xCC0D1525)
    val PanelBorder = Color(0x5538BDF8)
    val CyanPrimary = Color(0xFF38BDF8)
    val EmeraldLock = Color(0xFF34D399)
    val AmberNight = Color(0xFFFBBF24)
    val CoralRawAdsb = Color(0xFFFB7185)
    val IndigoHighAlt = Color(0xFF818CF8)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
}

private val FlightDarkColorScheme = darkColorScheme(
    primary = HudColors.CyanPrimary,
    onPrimary = Color(0xFF041422),
    secondary = HudColors.EmeraldLock,
    onSecondary = Color(0xFF022014),
    tertiary = HudColors.AmberNight,
    background = HudColors.DeepNightBg,
    surface = Color(0xFF0E1729),
    surfaceVariant = Color(0xFF17233B),
    onBackground = HudColors.TextPrimary,
    onSurface = HudColors.TextPrimary,
    onSurfaceVariant = HudColors.TextSecondary,
    outline = HudColors.PanelBorder
)

@Composable
fun FlightTrackArTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlightDarkColorScheme,
        content = content
    )
}
