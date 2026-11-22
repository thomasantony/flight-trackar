package com.flighttrackar.math

import com.flighttrackar.model.CalibrationOffsets
import com.flighttrackar.model.CameraIntrinsics
import com.flighttrackar.model.DeviceOrientation
import com.flighttrackar.model.EnuVector
import com.flighttrackar.vision.CameraAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathAndProjectionTest {

    @Test
    fun geodeticToEnu_directlyAboveObserver_isPureUp() {
        val enu = GeoMath.geodeticToEnu(
            observerLatDeg = 37.6213,
            observerLonDeg = -122.3790,
            observerAltMeters = 10.0,
            targetLatDeg = 37.6213,
            targetLonDeg = -122.3790,
            targetAltMeters = 10010.0
        )
        assertEquals(0.0, enu.east, 0.01)
        assertEquals(0.0, enu.north, 0.01)
        assertEquals(10000.0, enu.up, 0.01)

        val azEl = GeoMath.enuToAzElRange(enu)
        assertEquals(90.0, azEl.elevationDeg, 0.01)
        assertEquals(10000.0, azEl.slantRangeMeters, 0.1)
    }

    @Test
    fun geodeticToEnu_sameAltitudeFarAway_showsEarthCurvatureDrop() {
        // ~0.45 deg North (~50 km away) at identical altitude -> Earth curvature drops ~196 m
        val enu = GeoMath.geodeticToEnu(
            observerLatDeg = 37.0,
            observerLonDeg = -122.0,
            observerAltMeters = 0.0,
            targetLatDeg = 37.45,
            targetLonDeg = -122.0,
            targetAltMeters = 0.0
        )
        assertEquals(0.0, enu.east, 1.0)
        assertTrue("Expected ~50 km North, got ${enu.north}", enu.north in 49500.0..50500.0)
        assertTrue("Expected negative Up due to Earth curvature (~ -196m), got ${enu.up}", enu.up in -220.0..-170.0)
    }

    @Test
    fun projection_phoneHeldVerticalFacingMagneticNorth_centersTargetAtTrueNorthPlusDeclination() {
        // Portrait phone held upright facing Magnetic North:
        // Device +X = Mag East (1,0,0), Device +Y = Up (0,0,1), Device +Z (out of screen toward user) = Mag South (0,-1,0)
        // Rear camera looks along Device -Z = Mag North (0,1,0).
        // Rotation matrix columns are device axes in Magnetic ENU:
        val rDevToMag = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        val orientation = DeviceOrientation(
            rotationMatrix = rDevToMag,
            magneticHeadingDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 0f
        )
        val intrinsics = CameraIntrinsics(
            sensorWidthMm = 8f,
            sensorHeightMm = 6f,
            focalLengthMm = 6.8f,
            zoomRatio = 1f,
            viewportWidthPx = 1000,
            viewportHeightPx = 2000
        )

        // Declination = +13 deg East => Magnetic North points at True Azimuth 13 deg.
        // An aircraft at True Azimuth +13 deg should land dead center (500, 1000)!
        val rad13 = Math.toRadians(13.0)
        val aircraftTrueEnu = EnuVector(
            east = 10000.0 * kotlin.math.sin(rad13),
            north = 10000.0 * kotlin.math.cos(rad13),
            up = 0.0
        )
        val proj = CameraProjection.projectEnuToScreen(
            trueEnu = aircraftTrueEnu,
            orientation = orientation,
            magneticDeclinationDeg = 13f,
            calibration = CalibrationOffsets(),
            intrinsics = intrinsics
        )
        assertTrue(proj.isInFront)
        assertTrue(proj.isInViewport)
        assertEquals(500f, proj.screenX, 0.5f)
        assertEquals(1000f, proj.screenY, 0.5f)

        // Target slightly right (+East) and higher (+Up) should have screenX > 500 and screenY < 1000
        val upperRight = EnuVector(
            east = aircraftTrueEnu.east + 1000.0,
            north = aircraftTrueEnu.north,
            up = 1000.0
        )
        val projUR = CameraProjection.projectEnuToScreen(
            trueEnu = upperRight,
            orientation = orientation,
            magneticDeclinationDeg = 13f,
            calibration = CalibrationOffsets(),
            intrinsics = intrinsics
        )
        assertTrue(projUR.screenX > 550f)
        assertTrue(projUR.screenY < 950f)

        // Target behind camera should not be in front
        val behind = EnuVector(east = 0.0, north = -10000.0, up = 0.0)
        val projBehind = CameraProjection.projectEnuToScreen(
            trueEnu = behind,
            orientation = orientation,
            magneticDeclinationDeg = 13f,
            calibration = CalibrationOffsets(),
            intrinsics = intrinsics
        )
        assertFalse(projBehind.isInFront)
    }

    @Test
    fun dynamicZoom_tightensFovByExactZoomRatio() {
        val base = CameraIntrinsics(
            sensorWidthMm = 8f,
            sensorHeightMm = 6f,
            focalLengthMm = 6.8f,
            zoomRatio = 1f,
            viewportWidthPx = 1080,
            viewportHeightPx = 2400
        )
        val tele5x = base.copy(zoomRatio = 5f)
        val f1 = CameraProjection.effectiveFocalLengthPx(base)
        val f5 = CameraProjection.effectiveFocalLengthPx(tele5x)
        assertEquals(f1 * 5f, f5, 0.01f)

        val (hFov1, _) = CameraProjection.effectiveFovDeg(base)
        val (hFov5, _) = CameraProjection.effectiveFovDeg(tele5x)
        assertTrue("5x tele FOV ($hFov5) should be much narrower than 1x ($hFov1)", hFov5 < hFov1 / 4.5f)
    }

    @Test
    fun calibrationDelta_shiftsPredictionOntoVisualLock() {
        val rDevToMag = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        val orientation = DeviceOrientation(rDevToMag, 0f, 0f, 0f)
        val intrinsics = CameraIntrinsics(viewportWidthPx = 1000, viewportHeightPx = 2000)
        val target = EnuVector(0.0, 10000.0, 0.0)

        // Predicted center is (500, 1000); suppose actual visual plane is at (620, 910) (right & higher)
        val (dAz, dPitch) = CameraProjection.screenErrorToCalibrationDeltaDeg(
            predictedX = 500f,
            predictedY = 1000f,
            visualX = 620f,
            visualY = 910f,
            intrinsics = intrinsics
        )
        assertTrue("Expected positive dAz, got $dAz", dAz > 0f)
        assertTrue("Expected positive dPitch, got $dPitch", dPitch > 0f)

        // Applying that calibration delta should move the projected target right onto (620, 910)
        val recalibrated = CameraProjection.projectEnuToScreen(
            trueEnu = target,
            orientation = orientation,
            magneticDeclinationDeg = 0f,
            calibration = CalibrationOffsets(azimuthOffsetDeg = dAz, pitchOffsetDeg = dPitch),
            intrinsics = intrinsics
        )
        assertEquals(620f, recalibrated.screenX, 2.5f)
        assertEquals(910f, recalibrated.screenY, 2.5f)
    }

    @Test
    fun cameraAnalyzer_rotatedToRaw_mapsCornersCorrectly() {
        // Raw 640x480 rotated 90 deg CW -> upright 480x640
        assertEquals(0 to 479, CameraAnalyzer.rotatedToRaw(0, 0, 640, 480, 90))
        assertEquals(0 to 0, CameraAnalyzer.rotatedToRaw(479, 0, 640, 480, 90))
        assertEquals(639 to 0, CameraAnalyzer.rotatedToRaw(479, 639, 640, 480, 90))
        // Rotated 270 deg CW -> upright 480x640
        assertEquals(639 to 0, CameraAnalyzer.rotatedToRaw(0, 0, 640, 480, 270))
        assertEquals(0 to 0, CameraAnalyzer.rotatedToRaw(0, 639, 640, 480, 270))
    }
}
