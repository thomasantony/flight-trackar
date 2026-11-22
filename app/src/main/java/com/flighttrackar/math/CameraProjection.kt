package com.flighttrackar.math

import com.flighttrackar.model.CalibrationOffsets
import com.flighttrackar.model.CameraIntrinsics
import com.flighttrackar.model.DeviceOrientation
import com.flighttrackar.model.EnuVector
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Projects 3D world vectors in True ENU into 2D camera preview screen coordinates,
 * accounting for:
 *  - Geomagnetic declination (True North vs Magnetic North)
 *  - User manual / auto-locked azimuth & pitch calibration offsets
 *  - Full 3D device rotation matrix (arbitrary pitch, roll, yaw, portrait or landscape)
 *  - CameraX FILL_CENTER center-crop aspect ratio scaling
 *  - Dynamic camera zoomRatio (e.g., 0.5x ultrawide through 5x/30x telephoto on Pixel 9 Pro)
 */
object CameraProjection {

    data class ProjectionResult(
        val screenX: Float,
        val screenY: Float,
        /** Depth along camera optical axis (+Z_cam forward). Positive = in front of camera. */
        val cameraDepthMeters: Double,
        val isInFront: Boolean,
        val isInViewport: Boolean
    )

    /**
     * Computes effective focal length in viewport pixels for CameraX PreviewView.ScaleType.FILL_CENTER.
     */
    fun effectiveFocalLengthPx(intrinsics: CameraIntrinsics): Float {
        val vw = intrinsics.viewportWidthPx.coerceAtLeast(1).toFloat()
        val vh = intrinsics.viewportHeightPx.coerceAtLeast(1).toFloat()
        val isPortraitViewport = vh >= vw

        // Phone camera sensors are physically landscape (sensorWidthMm > sensorHeightMm).
        // When rotated into a portrait viewport, the sensor's shorter side maps horizontally.
        val sensorLong = max(intrinsics.sensorWidthMm, intrinsics.sensorHeightMm).coerceAtLeast(0.1f)
        val sensorShort = minOf(intrinsics.sensorWidthMm, intrinsics.sensorHeightMm).coerceAtLeast(0.1f)
        val orientedSensorW = if (isPortraitViewport) sensorShort else sensorLong
        val orientedSensorH = if (isPortraitViewport) sensorLong else sensorShort

        // FILL_CENTER scales uniformly so the smaller scale dimension fills completely and the other crops.
        val pxPerMm = max(vw / orientedSensorW, vh / orientedSensorH)
        return intrinsics.focalLengthMm * intrinsics.zoomRatio.coerceAtLeast(0.1f) * pxPerMm
    }

    /**
     * Computes horizontal and vertical viewport field of view in degrees at the current zoom.
     */
    fun effectiveFovDeg(intrinsics: CameraIntrinsics): Pair<Float, Float> {
        val fPx = effectiveFocalLengthPx(intrinsics).toDouble()
        val hFov = Math.toDegrees(2.0 * atan2(intrinsics.viewportWidthPx / 2.0, fPx)).toFloat()
        val vFov = Math.toDegrees(2.0 * atan2(intrinsics.viewportHeightPx / 2.0, fPx)).toFloat()
        return hFov to vFov
    }

    /**
     * Projects a 3D True ENU vector onto the camera viewport.
     */
    fun projectEnuToScreen(
        trueEnu: EnuVector,
        orientation: DeviceOrientation,
        magneticDeclinationDeg: Float,
        calibration: CalibrationOffsets,
        intrinsics: CameraIntrinsics,
        viewportMarginPx: Float = 80f
    ): ProjectionResult {
        // Step 1: Rotate True ENU into Magnetic ENU (and apply user azimuth nudge).
        // Magnetic North is rotated clockwise by +declination from True North, so:
        //   e_mag = e_true * cos(d) - n_true * sin(d)
        //   n_mag = e_true * sin(d) + n_true * cos(d)
        // User calibration.azimuthOffsetDeg shifts projected labels clockwise (+right on screen)
        // by rotating world target azimuth by +azimuthOffsetDeg before converting to Magnetic ENU:
        // effectiveAngle = declination - azimuthOffsetDeg.
        val azAngleRad = Math.toRadians((magneticDeclinationDeg - calibration.azimuthOffsetDeg).toDouble())
        val cosA = cos(azAngleRad)
        val sinA = sin(azAngleRad)

        val eMag = trueEnu.east * cosA - trueEnu.north * sinA
        val nMag = trueEnu.east * sinA + trueEnu.north * cosA
        val uMag = trueEnu.up

        // Step 2: Transform from Magnetic ENU to screen-aligned device coordinates.
        // orientation.rotationMatrix maps Device -> Magnetic ENU, so its transpose maps Magnetic ENU -> Device.
        val r = orientation.rotationMatrix
        val devX = r[0] * eMag + r[3] * nMag + r[6] * uMag
        val devY = r[1] * eMag + r[4] * nMag + r[7] * uMag
        val devZ = r[2] * eMag + r[5] * nMag + r[8] * uMag

        // Step 3: Convert screen-aligned device frame (+X right, +Y up, +Z out of screen)
        // to camera optical frame (+X_cam right, +Y_cam down, +Z_cam forward along rear camera -Z).
        var camX = devX
        var camY = -devY
        var camZ = -devZ

        // Step 4: Apply user pitch calibration offset around camera +X axis.
        // Positive pitchOffsetDeg shifts projected targets UP (smaller screenY / negative camY).
        if (calibration.pitchOffsetDeg != 0f) {
            val pRad = Math.toRadians(calibration.pitchOffsetDeg.toDouble())
            val cosP = cos(pRad)
            val sinP = sin(pRad)
            val newY = camY * cosP - camZ * sinP
            val newZ = camY * sinP + camZ * cosP
            camY = newY
            camZ = newZ
        }

        if (camZ <= 0.1) {
            return ProjectionResult(
                screenX = -10000f,
                screenY = -10000f,
                cameraDepthMeters = camZ,
                isInFront = false,
                isInViewport = false
            )
        }

        val fPx = effectiveFocalLengthPx(intrinsics).toDouble()
        val cx = intrinsics.viewportWidthPx / 2.0
        val cy = intrinsics.viewportHeightPx / 2.0

        val u = (cx + fPx * (camX / camZ)).toFloat()
        val v = (cy + fPx * (camY / camZ)).toFloat()

        val inBounds = u >= -viewportMarginPx &&
            u <= intrinsics.viewportWidthPx + viewportMarginPx &&
            v >= -viewportMarginPx &&
            v <= intrinsics.viewportHeightPx + viewportMarginPx

        return ProjectionResult(
            screenX = u,
            screenY = v,
            cameraDepthMeters = camZ,
            isInFront = true,
            isInViewport = inBounds
        )
    }

    /**
     * Converts a pixel offset between a visually detected aircraft and the EKF-predicted
     * screen point into incremental (deltaAzimuthDeg, deltaPitchDeg) calibration corrections.
     *
     * If visualX > predictedX (actual plane is to the right of predicted reticle),
     * we need a positive azimuthOffsetDeg to shift future predictions right onto the plane.
     * If visualY < predictedY (actual plane is higher on screen than predicted reticle),
     * we need a positive pitchOffsetDeg to shift future predictions up onto the plane.
     */
    fun screenErrorToCalibrationDeltaDeg(
        predictedX: Float,
        predictedY: Float,
        visualX: Float,
        visualY: Float,
        intrinsics: CameraIntrinsics,
        deviceRollDeg: Float = 0f,
        elevationDeg: Float = 0f
    ): Pair<Float, Float> {
        val fPx = effectiveFocalLengthPx(intrinsics).toDouble().coerceAtLeast(1.0)
        val dxPx = (visualX - predictedX).toDouble()
        val dyPx = (predictedY - visualY).toDouble() // Positive = visual is higher than predicted

        // Un-rotate screen error by device roll so horizontal/vertical align with horizon
        val rollRad = Math.toRadians(deviceRollDeg.toDouble())
        val cosR = cos(rollRad)
        val sinR = sin(rollRad)
        val horizPx = dxPx * cosR - dyPx * sinR
        val vertPx = dxPx * sinR + dyPx * cosR

        // Near zenith, azimuth angular change scales by 1/cos(elevation); clamp to avoid blow-up.
        val cosEl = cos(Math.toRadians(elevationDeg.toDouble())).coerceAtLeast(0.25)
        val deltaAzDeg = Math.toDegrees(atan2(horizPx, fPx)) / cosEl
        val deltaPitchDeg = Math.toDegrees(atan2(vertPx, fPx))

        return deltaAzDeg.toFloat() to deltaPitchDeg.toFloat()
    }

    /**
     * Estimates the apparent angular diameter (in viewport pixels) of an aircraft of given
     * physical wingspan/length at a given slant range.
     */
    fun expectedApparentSizePx(
        physicalSizeMeters: Double,
        slantRangeMeters: Double,
        intrinsics: CameraIntrinsics
    ): Float {
        if (slantRangeMeters <= 1.0) return 100f
        val fPx = effectiveFocalLengthPx(intrinsics).toDouble()
        return (fPx * (physicalSizeMeters / slantRangeMeters)).toFloat().coerceIn(4f, 600f)
    }
}
