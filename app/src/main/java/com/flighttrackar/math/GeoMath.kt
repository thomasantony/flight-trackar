package com.flighttrackar.math

import com.flighttrackar.model.EnuVector
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 ellipsoidal geodesy and local East-North-Up (ENU) coordinate transforms.
 * Exact ECEF -> ENU accounts for Earth curvature automatically, which matters
 * significantly at 20-100 km aircraft slant ranges.
 */
object GeoMath {
    // WGS84 ellipsoid constants
    private const val WGS84_A = 6378137.0 // Semi-major axis (m)
    private const val WGS84_F = 1.0 / 298.257223563
    private const val WGS84_E2 = WGS84_F * (2.0 - WGS84_F) // First eccentricity squared

    const val FEET_TO_METERS = 0.3048
    const val METERS_TO_FEET = 1.0 / FEET_TO_METERS
    const val KNOTS_TO_MPS = 0.5144444444444445
    const val MPS_TO_KNOTS = 1.0 / KNOTS_TO_MPS
    const val FPM_TO_MPS = FEET_TO_METERS / 60.0
    const val MPS_TO_FPM = 60.0 / FEET_TO_METERS
    const val NM_TO_METERS = 1852.0
    const val METERS_TO_NM = 1.0 / NM_TO_METERS
    const val MPS_TO_KMH = 3.6

    data class EcefVector(val x: Double, val y: Double, val z: Double)

    data class AzElRange(
        /** Clockwise from True North in [0, 360) degrees. */
        val azimuthDeg: Double,
        /** Elevation above local horizontal plane in [-90, 90] degrees. */
        val elevationDeg: Double,
        /** 3D slant distance in meters. */
        val slantRangeMeters: Double
    )

    /**
     * Converts WGS84 geodetic coordinates to Earth-Centered Earth-Fixed (ECEF) meters.
     */
    fun geodeticToEcef(latDeg: Double, lonDeg: Double, altMeters: Double): EcefVector {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val sinLon = sin(lon)
        val cosLon = cos(lon)

        val n = WGS84_A / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)
        val x = (n + altMeters) * cosLat * cosLon
        val y = (n + altMeters) * cosLat * sinLon
        val z = (n * (1.0 - WGS84_E2) + altMeters) * sinLat
        return EcefVector(x, y, z)
    }

    /**
     * Converts a target's WGS84 geodetic coordinates to local ENU meters
     * centered at the observer's WGS84 coordinates.
     */
    fun geodeticToEnu(
        observerLatDeg: Double,
        observerLonDeg: Double,
        observerAltMeters: Double,
        targetLatDeg: Double,
        targetLonDeg: Double,
        targetAltMeters: Double
    ): EnuVector {
        val obs = geodeticToEcef(observerLatDeg, observerLonDeg, observerAltMeters)
        val tgt = geodeticToEcef(targetLatDeg, targetLonDeg, targetAltMeters)

        val dx = tgt.x - obs.x
        val dy = tgt.y - obs.y
        val dz = tgt.z - obs.z

        val lat = Math.toRadians(observerLatDeg)
        val lon = Math.toRadians(observerLonDeg)
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val sinLon = sin(lon)
        val cosLon = cos(lon)

        val east = -sinLon * dx + cosLon * dy
        val north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
        val up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz

        return EnuVector(east, north, up)
    }

    /**
     * Converts a local ENU vector to True Azimuth, Elevation, and Slant Range.
     */
    fun enuToAzElRange(enu: EnuVector): AzElRange {
        val horiz = hypot(enu.east, enu.north)
        val slant = sqrt(enu.east * enu.east + enu.north * enu.north + enu.up * enu.up)
        val azRad = atan2(enu.east, enu.north)
        val azDeg = normalizeDegrees360(Math.toDegrees(azRad))
        val elDeg = Math.toDegrees(atan2(enu.up, horiz))
        return AzElRange(azDeg, elDeg, slant)
    }

    /**
     * Computes a bounding box (minLat, minLon, maxLat, maxLon) in degrees
     * around (latDeg, lonDeg) with radius in nautical miles (for OpenSky API).
     */
    fun boundingBoxDeg(latDeg: Double, lonDeg: Double, radiusNm: Double): DoubleArray {
        val radiusKm = radiusNm * 1.852
        val deltaLat = radiusKm / 111.32
        val cosLat = cos(Math.toRadians(latDeg)).coerceAtLeast(0.05)
        val deltaLon = radiusKm / (111.32 * cosLat)
        return doubleArrayOf(
            (latDeg - deltaLat).coerceIn(-90.0, 90.0),
            (lonDeg - deltaLon).coerceIn(-180.0, 180.0),
            (latDeg + deltaLat).coerceIn(-90.0, 90.0),
            (lonDeg + deltaLon).coerceIn(-180.0, 180.0)
        )
    }

    /**
     * Normalizes an angle in degrees to [0, 360).
     */
    fun normalizeDegrees360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0.0) d += 360.0
        return d
    }

    /**
     * Wraps an angle difference in degrees to (-180, 180].
     */
    fun wrapAngleDiffDeg(deg: Double): Double {
        var d = (deg + 180.0) % 360.0
        if (d <= 0.0) d += 360.0
        return d - 180.0
    }

    /**
     * Wraps an angle in radians to (-PI, PI].
     */
    fun wrapAngleRad(rad: Double): Double {
        var r = (rad + PI) % (2.0 * PI)
        if (r <= 0.0) r += 2.0 * PI
        return r - PI
    }

    /**
     * Approximate inverse ENU offset back to geodetic lat/lon/alt (accurate within meters at < 150 km).
     */
    fun enuOffsetToGeodetic(
        originLatDeg: Double,
        originLonDeg: Double,
        originAltMeters: Double,
        enu: EnuVector
    ): Triple<Double, Double, Double> {
        val latRad = Math.toRadians(originLatDeg)
        val metersPerDegLat = 111132.92 - 559.82 * cos(2 * latRad) + 1.175 * cos(4 * latRad)
        val metersPerDegLon = 111412.84 * cos(latRad) - 93.5 * cos(3 * latRad)
        val lat = originLatDeg + enu.north / metersPerDegLat
        val lon = originLonDeg + enu.east / metersPerDegLon.coerceAtLeast(1.0)
        val alt = originAltMeters + enu.up
        return Triple(lat, lon, alt)
    }
}
