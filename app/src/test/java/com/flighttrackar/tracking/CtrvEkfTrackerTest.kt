package com.flighttrackar.tracking

import com.flighttrackar.model.EnuVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class CtrvEkfTrackerTest {

    @Test
    fun straightFlight_compensatesAdsbDelayAccurately() {
        // Aircraft flying due East (track = 90 deg) at 200 m/s, climbing at 5 m/s
        val tracker = CtrvEkfTracker(
            initialEnu = EnuVector(1000.0, 2000.0, 3000.0),
            initialGroundSpeedMps = 200.0,
            initialTrackRad = Math.toRadians(90.0),
            initialTurnRateRadPerSec = 0.0,
            initialVerticalRateMps = 5.0,
            initialTimestampSec = 100.0
        )

        // Predict 5.0 seconds into the future
        val pred = tracker.predictStateAt(105.0)
        assertEquals(2000.0, pred.enu.east, 0.5)
        assertEquals(2000.0, pred.enu.north, 0.5)
        assertEquals(3025.0, pred.enu.up, 0.5)
        assertEquals(90.0, pred.trackDeg, 0.1)
    }

    @Test
    fun constantTurnRate_predictsCurvedArcMoreAccuratelyThanStraightLine() {
        // Aircraft flying North (0 deg) at 150 m/s turning right at +3 deg/s (standard rate turn!)
        val v = 150.0
        val omegaDeg = 3.0
        val omegaRad = Math.toRadians(omegaDeg)
        val dt = 6.0

        val tracker = CtrvEkfTracker(
            initialEnu = EnuVector(0.0, 0.0, 4000.0),
            initialGroundSpeedMps = v,
            initialTrackRad = 0.0,
            initialTurnRateRadPerSec = omegaRad,
            initialVerticalRateMps = 0.0,
            initialTimestampSec = 10.0
        )

        val pred = tracker.predictStateAt(10.0 + dt)

        // Exact analytical CTRV position after dt=6s (18 deg right turn)
        val trueEast = (v / omegaRad) * (1.0 - cos(omegaRad * dt))
        val trueNorth = (v / omegaRad) * sin(omegaRad * dt)

        // Straight-line error would be ~140m East; CTRV prediction should be within a few meters!
        val ctrvErr = hypot(pred.enu.east - trueEast, pred.enu.north - trueNorth)
        val straightErr = hypot(0.0 - trueEast, v * dt - trueNorth)
        assertTrue("CTRV error ($ctrvErr m) should be tiny compared to straight-line ($straightErr m)", ctrvErr < 15.0)
        assertTrue("Straight-line error should be large ($straightErr m)", straightErr > 100.0)
    }

    @Test
    fun missingTelemetryTurnRate_ekfInfersTurnRateFromHeadingUpdates() {
        // Simulate OpenSky feed where turnRateDegPerSec == null while plane turns right at +2.5 deg/s
        val v = 180.0
        val omegaDeg = 2.5
        val omegaRad = Math.toRadians(omegaDeg)
        var east = 0.0
        var north = 0.0
        var psi = Math.toRadians(355.0) // Also tests 360->0 heading wrap-around!

        val tracker = CtrvEkfTracker(
            initialEnu = EnuVector(east, north, 5000.0),
            initialGroundSpeedMps = v,
            initialTrackRad = psi,
            initialTurnRateRadPerSec = 0.0,
            initialVerticalRateMps = 0.0,
            initialTimestampSec = 0.0
        )

        val stepDt = 2.0
        for (step in 1..6) {
            val psiNew = psi + omegaRad * stepDt
            east += (v / omegaRad) * (cos(psi) - cos(psiNew))
            north += (v / omegaRad) * (sin(psiNew) - sin(psi))
            psi = psiNew

            val trackDeg = ((Math.toDegrees(psi) % 360.0) + 360.0) % 360.0
            tracker.update(
                measuredEnu = EnuVector(east, north, 5000.0),
                groundSpeedMps = v,
                trackDeg = trackDeg,
                turnRateDegPerSec = null, // No turn rate reported!
                verticalRateMps = 0.0,
                measurementTimeSec = step * stepDt,
                isGeomAltitude = true
            )
        }

        val state = tracker.predictStateAt(12.0)
        assertTrue(
            "Inferred turn rate (${state.turnRateDegPerSec} deg/s) should approach +2.5 deg/s",
            state.turnRateDegPerSec in 1.6..3.4
        )
    }
}
