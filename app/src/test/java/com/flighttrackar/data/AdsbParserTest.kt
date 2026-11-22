package com.flighttrackar.data

import com.flighttrackar.math.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsbParserTest {

    private val repo = AdsbRepository()

    @Test
    fun parseReadsbV2Json_parsesAirborneAndGroundAircraftWithTurnRateAndAge() {
        val sampleJson = """
            {
              "ac": [
                {
                  "hex": "A1B2C3",
                  "flight": "UAL123  ",
                  "r": "N12345",
                  "t": "B738",
                  "desc": "BOEING 737-800",
                  "lat": 37.65,
                  "lon": -122.40,
                  "alt_baro": 10000,
                  "alt_geom": 10200,
                  "gs": 250.0,
                  "track": 182.5,
                  "track_rate": -1.4,
                  "baro_rate": 1200,
                  "squawk": "4512",
                  "seen_pos": 1.8
                },
                {
                  "hex": "b00001",
                  "flight": "SWA99",
                  "lat": 37.62,
                  "lon": -122.38,
                  "alt_baro": "ground",
                  "gs": 12.0,
                  "seen_pos": 0.4
                }
              ]
            }
        """.trimIndent()

        val parsed = repo.parseReadsbV2Json(sampleJson, "airplanes.live", nowMonotonicSec = 500.0)
        assertEquals(2, parsed.size)

        val airborne = parsed[0]
        assertEquals("a1b2c3", airborne.icao24)
        assertEquals("UAL123", airborne.callsign)
        assertEquals("N12345", airborne.registration)
        assertEquals("B738", airborne.aircraftType)
        assertFalse(airborne.onGround)
        assertEquals(10200.0 * GeoMath.FEET_TO_METERS, airborne.geomAltitudeMeters!!, 0.5)
        assertEquals(250.0 * GeoMath.KNOTS_TO_MPS, airborne.groundSpeedMps!!, 0.2)
        assertEquals(182.5, airborne.trackDeg!!, 0.01)
        assertEquals(-1.4, airborne.turnRateDegPerSec!!, 0.01)
        assertEquals(1200.0 * GeoMath.FPM_TO_MPS, airborne.verticalRateMps!!, 0.1)
        assertEquals(1.8, airborne.positionAgeSec, 0.01)
        assertEquals(498.2, airborne.measurementTimeSec, 0.01)

        val grounded = parsed[1]
        assertTrue(grounded.onGround)
        assertNull(grounded.baroAltitudeMeters)
    }

    @Test
    fun parseOpenSkyJson_parsesStateVectorsAndComputesPositionAge() {
        val sampleJson = """
            {
              "time": 1700000100,
              "states": [
                ["a8c123", "DAL404  ", "United States", 1700000095, 1700000099, -122.35, 37.70, 9144.0, false, 230.5, 45.0, -3.2, null, 9300.0, "2104", false, 0]
              ]
            }
        """.trimIndent()

        val parsed = repo.parseOpenSkyJson(
            rawJson = sampleJson,
            nowMonotonicSec = 1000.0,
            nowEpochSec = 1700000100.0
        )
        assertEquals(1, parsed.size)
        val ac = parsed[0]
        assertEquals("a8c123", ac.icao24)
        assertEquals("DAL404", ac.callsign)
        assertEquals(9300.0, ac.geomAltitudeMeters!!, 0.1)
        assertEquals(9144.0, ac.baroAltitudeMeters!!, 0.1)
        assertEquals(230.5, ac.groundSpeedMps!!, 0.1)
        assertEquals(45.0, ac.trackDeg!!, 0.01)
        assertNull(ac.turnRateDegPerSec)
        assertEquals(5.0, ac.positionAgeSec, 0.01)
        assertEquals(995.0, ac.measurementTimeSec, 0.01)
    }
}
