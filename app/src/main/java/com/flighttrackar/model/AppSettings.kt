package com.flighttrackar.model

enum class AdsbProviderChoice(val label: String, val subtitle: String) {
    AUTO(
        "Auto (Best Open)",
        "airplanes.live → adsb.lol → OpenSky fallback"
    ),
    AIRPLANES_LIVE(
        "airplanes.live",
        "~2s updates, turn-rate & position age, no API key"
    ),
    ADSB_LOL(
        "adsb.lol",
        "~2s updates, turn-rate & position age, no API key"
    ),
    OPENSKY(
        "OpenSky Network",
        "Classic open ADS-B API (~8s poll for rate limits)"
    ),
    DEMO_SIM(
        "Demo Simulation",
        "Synthetic nearby flights for indoor / offline testing"
    )
}

enum class VisionMode(val label: String) {
    AUTO("Auto"),
    DAY("Day"),
    NIGHT("Night"),
    OFF("Off")
}

enum class UnitSystem(val label: String) {
    AVIATION("Aviation (ft, kt, nm)"),
    METRIC("Metric (m, km/h, km)")
}

/**
 * Manual and auto-locked sensor alignment offsets to cancel magnetometer drift & mounting bias.
 */
data class CalibrationOffsets(
    /** Added to true azimuth alignment (degrees). Positive shifts labels right/clockwise. */
    val azimuthOffsetDeg: Float = 0f,
    /** Added to camera pitch alignment (degrees). Positive shifts labels up. */
    val pitchOffsetDeg: Float = 0f,
    /** When enabled, gently trims offsets using high-confidence visual locks. */
    val autoTrimFromVisualLock: Boolean = false
)

data class AppSettings(
    val provider: AdsbProviderChoice = AdsbProviderChoice.AUTO,
    val visionMode: VisionMode = VisionMode.AUTO,
    val unitSystem: UnitSystem = UnitSystem.AVIATION,
    /** Search radius around observer in nautical miles. */
    val searchRadiusNm: Int = 40,
    /** Extra future lead time in seconds beyond real-time latency compensation (0 = live now). */
    val extraPredictionLeadSec: Float = 0f,
    /** Show Debug / Tracker HUD (raw vs EKF vector, ROI boxes, CV stats). */
    val showDebugHud: Boolean = false,
    /** Minimum elevation angle in degrees to show on AR view. */
    val minElevationDeg: Float = 0f,
    val calibration: CalibrationOffsets = CalibrationOffsets()
)
