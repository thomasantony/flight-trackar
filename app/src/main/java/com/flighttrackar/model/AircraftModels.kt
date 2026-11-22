package com.flighttrackar.model

/**
 * Observer's geographic position in WGS84.
 */
data class ObserverLocation(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** Altitude in meters (WGS84 ellipsoid when from GPS). */
    val altitudeMeters: Double,
    val accuracyMeters: Float = 0f,
    /** Magnetic declination in degrees (East positive). */
    val magneticDeclinationDeg: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * 3D local East-North-Up vector in meters relative to the observer.
 */
data class EnuVector(
    val east: Double,
    val north: Double,
    val up: Double
) {
    val horizontalDistanceMeters: Double get() = kotlin.math.hypot(east, north)
    val slantRangeMeters: Double get() = kotlin.math.sqrt(east * east + north * north + up * up)
}

/**
 * Device orientation represented as a 3x3 row-major rotation matrix mapping
 * screen-aligned device coordinates (+X right, +Y up, +Z out of screen)
 * to Android Magnetic ENU world coordinates (+X Mag East, +Y Mag North, +Z Up).
 */
data class DeviceOrientation(
    val rotationMatrix: FloatArray,
    /** Magnetic azimuth of camera optical axis (-Z) in degrees [0, 360). */
    val magneticHeadingDeg: Float,
    /** Pitch of camera optical axis above horizon in degrees [-90, 90]. */
    val pitchDeg: Float,
    /** Roll of device in degrees [-180, 180]. */
    val rollDeg: Float,
    /** Sensor accuracy from SensorManager (0..3). */
    val accuracy: Int = 3,
    val timestampNs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceOrientation) return false
        return rotationMatrix.contentEquals(other.rotationMatrix) &&
            magneticHeadingDeg == other.magneticHeadingDeg &&
            pitchDeg == other.pitchDeg &&
            rollDeg == other.rollDeg &&
            accuracy == other.accuracy &&
            timestampNs == other.timestampNs
    }

    override fun hashCode(): Int {
        var result = rotationMatrix.contentHashCode()
        result = 31 * result + magneticHeadingDeg.hashCode()
        result = 31 * result + pitchDeg.hashCode()
        result = 31 * result + rollDeg.hashCode()
        result = 31 * result + accuracy
        result = 31 * result + timestampNs.hashCode()
        return result
    }
}

/**
 * Physical and runtime intrinsics for the active rear camera.
 */
data class CameraIntrinsics(
    /** Physical sensor width in landscape orientation (mm). */
    val sensorWidthMm: Float = 8.0f,
    /** Physical sensor height in landscape orientation (mm). */
    val sensorHeightMm: Float = 6.0f,
    /** Base 1.0x focal length (mm). */
    val focalLengthMm: Float = 6.8f,
    /** Active zoom ratio (e.g., 0.5x..30x on Pixel 9 Pro). */
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 10.0f,
    /** Current preview surface dimensions in pixels. */
    val viewportWidthPx: Int = 1080,
    val viewportHeightPx: Int = 2400,
    /** Sensor natural orientation relative to device upright (typically 90 deg on phones). */
    val sensorOrientationDeg: Int = 90
)

/**
 * Normalized ADS-B report parsed from any supported provider.
 */
data class AdsbObservation(
    val icao24: String,
    val callsign: String?,
    val registration: String?,
    val aircraftType: String?,
    val description: String?,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** Geometric altitude in meters (preferred, WGS84) if available. */
    val geomAltitudeMeters: Double?,
    /** Barometric altitude in meters if available. */
    val baroAltitudeMeters: Double?,
    /** Ground speed in m/s if available. */
    val groundSpeedMps: Double?,
    /** True track / heading clockwise from True North in degrees [0, 360). */
    val trackDeg: Double?,
    /** Reported turn rate in degrees/second (positive = right/clockwise turn). */
    val turnRateDegPerSec: Double?,
    /** Vertical rate in m/s (positive = climbing). */
    val verticalRateMps: Double?,
    val squawk: String?,
    val onGround: Boolean,
    /** Age of the position fix in seconds at the time the HTTP response was received. */
    val positionAgeSec: Double,
    /** Monotonic timestamp (seconds) when this position was actually measured. */
    val measurementTimeSec: Double,
    val sourceName: String
) {
    val bestAltitudeMeters: Double? get() = geomAltitudeMeters ?: baroAltitudeMeters
    val displayLabel: String get() = callsign?.takeIf { it.isNotBlank() } ?: registration?.takeIf { it.isNotBlank() } ?: icao24.uppercase()
}

/**
 * Snapshot of an aircraft tracked by the 3D CTRV Extended Kalman Filter.
 */
data class TrackedAircraft(
    val icao24: String,
    val callsign: String?,
    val registration: String?,
    val aircraftType: String?,
    val description: String?,
    val squawk: String?,
    val sourceName: String,
    /** Raw last ADS-B observation in local ENU (for Debug HUD comparison). */
    val rawEnu: EnuVector,
    /** EKF predicted position in local ENU at the target render time. */
    val predictedEnu: EnuVector,
    /** Short future trajectory points (e.g. +2s, +4s, +6s) in ENU for HUD/debug. */
    val futureTrajectoryEnu: List<EnuVector>,
    /** Filtered horizontal ground speed (m/s). */
    val groundSpeedMps: Double,
    /** Filtered true track / heading (degrees, clockwise from True North). */
    val trackDeg: Double,
    /** Filtered or estimated turn rate (degrees/second). */
    val turnRateDegPerSec: Double,
    /** Filtered vertical rate (m/s). */
    val verticalRateMps: Double,
    /** Whether turn rate came directly from ADS-B telemetry vs inferred by EKF. */
    val hasTelemetryTurnRate: Boolean,
    /** Filtered altitude in meters (WGS84 / best available). */
    val altitudeMeters: Double,
    /** Raw barometric altitude in meters (for detail display). */
    val baroAltitudeMeters: Double?,
    /** Raw geometric altitude in meters (for detail display). */
    val geomAltitudeMeters: Double?,
    /** Total seconds extrapolated from the raw ADS-B position fix timestamp to render time. */
    val totalPredictionLeadSec: Double,
    /** Age of last ADS-B position fix (seconds). */
    val dataAgeSec: Double,
    /** 1-sigma horizontal position uncertainty from EKF covariance (meters). */
    val positionSigmaMeters: Double,
    /** Number of ADS-B updates ingested by this track. */
    val updateCount: Int
) {
    val displayLabel: String get() = callsign?.takeIf { it.isNotBlank() } ?: registration?.takeIf { it.isNotBlank() } ?: icao24.uppercase()
}

enum class DetectedSkyMode {
    DAY,
    NIGHT,
    OBSTRUCTED
}

/**
 * Result of the real-time camera CV detector for a single aircraft ROI.
 */
data class VisualDetection(
    val icao24: String,
    /** Normalized viewport coordinates [0..1] for center & box size. */
    val centerXNorm: Float,
    val centerYNorm: Float,
    val boxWidthNorm: Float,
    val boxHeightNorm: Float,
    /** Confidence in [0..1]. */
    val confidence: Float,
    val modeUsed: DetectedSkyMode,
    /** True when night-mode temporal analyzer detected blinking strobe/beacon modulation. */
    val strobeDetected: Boolean = false,
    /** Contrast SNR ratio. */
    val snr: Float = 0f,
    /** Search ROI in normalized coordinates (for debug HUD). */
    val roiLeftNorm: Float = 0f,
    val roiTopNorm: Float = 0f,
    val roiRightNorm: Float = 0f,
    val roiBottomNorm: Float = 0f,
    val candidateCount: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Global frame-level sky telemetry from the CV pipeline.
 */
data class SkyFrameStats(
    val activeMode: DetectedSkyMode = DetectedSkyMode.DAY,
    val meanLuminance: Float = 128f,
    val skyConfidence: Float = 0f,
    val processingTimeMs: Float = 0f,
    val analyzedFps: Float = 0f
)

/**
 * Screen-projected aircraft ready to render in the AR overlay and pass as ROI hint to CV.
 */
data class ProjectedAircraft(
    val track: TrackedAircraft,
    /** EKF predicted screen coordinates in pixels. */
    val predictedScreenX: Float,
    val predictedScreenY: Float,
    /** Raw delayed ADS-B screen coordinates in pixels (for Debug HUD). */
    val rawScreenX: Float?,
    val rawScreenY: Float?,
    /** Future trajectory screen points in pixels. */
    val futureScreenPoints: List<Pair<Float, Float>>,
    /** Predicted screen velocity (px/sec) for night-mode motion filtering. */
    val screenVelocityXPxPerSec: Float,
    val screenVelocityYPxPerSec: Float,
    /** True bearing clockwise from True North (degrees). */
    val azimuthDeg: Double,
    /** Elevation angle above horizon (degrees). */
    val elevationDeg: Double,
    /** Slant range in meters. */
    val slantRangeMeters: Double,
    /** True if the predicted position is in front of the camera and within viewport bounds (+ margin). */
    val isInViewport: Boolean,
    /** Estimated angular span of a typical airliner (~40m) at this distance, in pixels. */
    val expectedApparentSizePx: Float,
    /** Matched visual bounding box from camera feed, if detected. */
    val visualDetection: VisualDetection? = null
)
