package com.flighttrackar.data

import com.flighttrackar.math.GeoMath
import com.flighttrackar.model.AdsbObservation
import com.flighttrackar.model.EnuVector
import com.flighttrackar.model.ObserverLocation
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates realistic synthetic aircraft flying overhead (including straight cruise,
 * climbing departure, and banking turn trajectories with simulated 2.5s ADS-B delay)
 * so the user can test AR overlays, EKF prediction, and HUD controls indoors or offline.
 */
class SimulatedAdsbProvider {

    private var startTimeSec: Double? = null

    fun generateObservations(
        observer: ObserverLocation,
        nowMonotonicSec: Double
    ): List<AdsbObservation> {
        val t0 = startTimeSec ?: nowMonotonicSec.also { startTimeSec = it }
        // Simulate 2.5s ADS-B report delay so the EKF's compensation is clearly visible in Debug HUD
        val simulatedDelaySec = 2.5
        val t = (nowMonotonicSec - t0 - simulatedDelaySec).coerceAtLeast(0.0)
        val measTimeSec = nowMonotonicSec - simulatedDelaySec

        val aircraft = mutableListOf<AdsbObservation>()

        // 1. Banking airliner circling gently around the observer at ~6 km range, 14,500 ft
        run {
            val radiusM = 6200.0
            val speedMps = 195.0 // ~380 kt
            val omegaRad = speedMps / radiusM // ~1.8 deg/s right turn
            val angle = omegaRad * t
            val east = radiusM * sin(angle)
            val north = radiusM * cos(angle)
            val up = 4400.0
            val headingDeg = GeoMath.normalizeDegrees360(Math.toDegrees(angle) + 90.0)
            val (lat, lon, alt) = GeoMath.enuOffsetToGeodetic(
                observer.latitudeDeg, observer.longitudeDeg, observer.altitudeMeters,
                EnuVector(east, north, up)
            )
            aircraft.add(
                AdsbObservation(
                    icao24 = "a1b2c3",
                    callsign = "UAL482",
                    registration = "N37482",
                    aircraftType = "B739",
                    description = "BOEING 737-900ER (SIM)",
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    geomAltitudeMeters = alt,
                    baroAltitudeMeters = alt - 35.0,
                    groundSpeedMps = speedMps,
                    trackDeg = headingDeg,
                    turnRateDegPerSec = Math.toDegrees(omegaRad),
                    verticalRateMps = 0.0,
                    squawk = "2415",
                    onGround = false,
                    positionAgeSec = simulatedDelaySec,
                    measurementTimeSec = measTimeSec,
                    sourceName = "Demo Sim"
                )
            )
        }

        // 2. High-altitude cruise jet crossing West -> East at FL340 (~10.4 km)
        run {
            val cycleT = (t % 140.0) - 70.0
            val speedMps = 245.0 // ~476 kt
            val east = cycleT * speedMps
            val north = 8500.0
            val up = 10400.0
            val (lat, lon, alt) = GeoMath.enuOffsetToGeodetic(
                observer.latitudeDeg, observer.longitudeDeg, observer.altitudeMeters,
                EnuVector(east, north, up)
            )
            aircraft.add(
                AdsbObservation(
                    icao24 = "a9d4e1",
                    callsign = "DAL912",
                    registration = "N812DN",
                    aircraftType = "A359",
                    description = "AIRBUS A350-900 (SIM)",
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    geomAltitudeMeters = alt,
                    baroAltitudeMeters = alt - 60.0,
                    groundSpeedMps = speedMps,
                    trackDeg = 90.0,
                    turnRateDegPerSec = 0.0,
                    verticalRateMps = 0.0,
                    squawk = "6210",
                    onGround = false,
                    positionAgeSec = simulatedDelaySec,
                    measurementTimeSec = measTimeSec,
                    sourceName = "Demo Sim"
                )
            )
        }

        // 3. Climbing regional jet heading NNW with steady climb rate
        run {
            val cycleT = t % 120.0
            val speedMps = 140.0 // ~272 kt
            val trackRad = Math.toRadians(335.0)
            val east = 4200.0 + speedMps * sin(trackRad) * cycleT
            val north = -4500.0 + speedMps * cos(trackRad) * cycleT
            val up = 1800.0 + 9.5 * cycleT // ~1870 fpm climb
            val (lat, lon, alt) = GeoMath.enuOffsetToGeodetic(
                observer.latitudeDeg, observer.longitudeDeg, observer.altitudeMeters,
                EnuVector(east, north, up)
            )
            aircraft.add(
                AdsbObservation(
                    icao24 = "ac5510",
                    callsign = "SWA1904",
                    registration = "N8812Q",
                    aircraftType = "B38M",
                    description = "BOEING 737 MAX 8 (SIM)",
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    geomAltitudeMeters = alt,
                    baroAltitudeMeters = alt - 20.0,
                    groundSpeedMps = speedMps,
                    trackDeg = 335.0,
                    turnRateDegPerSec = 0.0,
                    verticalRateMps = 9.5,
                    squawk = "3341",
                    onGround = false,
                    positionAgeSec = simulatedDelaySec,
                    measurementTimeSec = measTimeSec,
                    sourceName = "Demo Sim"
                )
            )
        }

        // 4. Descending approach aircraft to the South-West
        run {
            val cycleT = t % 110.0
            val speedMps = 115.0 // ~223 kt
            val trackRad = Math.toRadians(45.0)
            val east = -9000.0 + speedMps * sin(trackRad) * cycleT
            val north = -7500.0 + speedMps * cos(trackRad) * cycleT
            val up = (3200.0 - 6.0 * cycleT).coerceAtLeast(600.0)
            val (lat, lon, alt) = GeoMath.enuOffsetToGeodetic(
                observer.latitudeDeg, observer.longitudeDeg, observer.altitudeMeters,
                EnuVector(east, north, up)
            )
            aircraft.add(
                AdsbObservation(
                    icao24 = "a448fe",
                    callsign = "ASA318",
                    registration = "N493AS",
                    aircraftType = "E75L",
                    description = "EMBRAER E175 (SIM)",
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    geomAltitudeMeters = alt,
                    baroAltitudeMeters = alt,
                    groundSpeedMps = speedMps,
                    trackDeg = 45.0,
                    turnRateDegPerSec = 0.0,
                    verticalRateMps = -6.0,
                    squawk = "1204",
                    onGround = false,
                    positionAgeSec = simulatedDelaySec,
                    measurementTimeSec = measTimeSec,
                    sourceName = "Demo Sim"
                )
            )
        }

        return aircraft
    }
}
