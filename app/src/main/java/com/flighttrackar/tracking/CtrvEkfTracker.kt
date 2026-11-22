package com.flighttrackar.tracking

import com.flighttrackar.math.GeoMath
import com.flighttrackar.model.EnuVector
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 7-state 3D Constant Turn Rate and Velocity (CTRV) Extended Kalman Filter
 * operating in a local East-North-Up (ENU) meter frame.
 *
 * State vector x:
 *   0: pE   (East position, m)
 *   1: pN   (North position, m)
 *   2: pU   (Up position, m)
 *   3: vH   (Horizontal ground speed, m/s)
 *   4: psi  (True track / heading clockwise from North, rad)
 *   5: omega (Turn rate d(psi)/dt, rad/s, positive = clockwise/right)
 *   6: vZ   (Vertical rate d(pU)/dt, m/s)
 */
class CtrvEkfTracker(
    initialEnu: EnuVector,
    initialGroundSpeedMps: Double,
    initialTrackRad: Double,
    initialTurnRateRadPerSec: Double,
    initialVerticalRateMps: Double,
    initialTimestampSec: Double
) {
    companion object {
        private const val N = 7
        private const val OMEGA_EPS = 1e-4
        // Exponential decay time constant for turn rate during extrapolation so a transient
        // turn doesn't cause unrealistic tight spirals during long ADS-B gaps.
        private const val TURN_RATE_DECAY_TAU_SEC = 45.0
        private const val MAX_TURN_RATE_RAD_S = 0.14 // ~8 deg/s max realistic maneuver
    }

    /** Current state vector [pE, pN, pU, vH, psi, omega, vZ]. */
    val x = DoubleArray(N)

    /** 7x7 row-major state covariance matrix P. */
    val p = DoubleArray(N * N)

    /** Monotonic timestamp (seconds) corresponding to the current filtered state. */
    var stateTimeSec: Double = initialTimestampSec
        private set

    var updateCount: Int = 1
        private set

    private var lastObservedTrackRad: Double? = initialTrackRad
    private var lastObservedTrackTimeSec: Double = initialTimestampSec

    init {
        x[0] = initialEnu.east
        x[1] = initialEnu.north
        x[2] = initialEnu.up
        x[3] = initialGroundSpeedMps.coerceIn(0.0, 500.0)
        x[4] = GeoMath.wrapAngleRad(initialTrackRad)
        x[5] = initialTurnRateRadPerSec.coerceIn(-MAX_TURN_RATE_RAD_S, MAX_TURN_RATE_RAD_S)
        x[6] = initialVerticalRateMps.coerceIn(-60.0, 60.0)

        // Initial covariance P0 (variances)
        setP(0, 0, 25.0 * 25.0)       // 25 m East
        setP(1, 1, 25.0 * 25.0)       // 25 m North
        setP(2, 2, 35.0 * 35.0)       // 35 m Up
        setP(3, 3, 10.0 * 10.0)       // 10 m/s speed
        setP(4, 4, 0.08 * 0.08)       // ~4.5 deg track
        setP(5, 5, 0.03 * 0.03)       // ~1.7 deg/s turn rate
        setP(6, 6, 4.0 * 4.0)         // 4 m/s vertical rate
    }

    data class PredictedState(
        val enu: EnuVector,
        val groundSpeedMps: Double,
        val trackDeg: Double,
        val turnRateDegPerSec: Double,
        val verticalRateMps: Double,
        val horizontalSigmaMeters: Double
    )

    /**
     * Advances the EKF state and covariance forward in time by dt seconds (mutating).
     */
    fun predictInPlace(dt: Double) {
        if (dt <= 1e-4) return
        // Sub-step large dt for better linearization stability
        val steps = (dt / 2.0).toInt().coerceAtLeast(1)
        val stepDt = dt / steps
        repeat(steps) {
            predictStep(stepDt)
        }
        stateTimeSec += dt
    }

    /**
     * Non-mutating prediction from the current filtered state to targetTimeSec,
     * used at 60 FPS for buttery-smooth AR rendering without perturbing filter covariance.
     */
    fun predictStateAt(targetTimeSec: Double): PredictedState {
        val dt = (targetTimeSec - stateTimeSec).coerceIn(-2.0, 45.0)
        val predX = propagateState(x, dt)

        // Approximate covariance growth for horizontal uncertainty reporting
        val sigmaPos0 = sqrt((getP(0, 0) + getP(1, 1)).coerceAtLeast(1.0) * 0.5)
        val sigmaVel = sqrt(getP(3, 3).coerceAtLeast(1.0))
        val sigmaOmega = sqrt(getP(5, 5).coerceAtLeast(1e-6))
        val absDt = abs(dt)
        val turnDrift = 0.5 * predX[3] * sigmaOmega * absDt * absDt
        val accelDrift = 0.5 * 1.5 * absDt * absDt
        val totalSigma = sqrt(sigmaPos0 * sigmaPos0 + (sigmaVel * absDt) * (sigmaVel * absDt) + turnDrift * turnDrift + accelDrift * accelDrift)

        return PredictedState(
            enu = EnuVector(predX[0], predX[1], predX[2]),
            groundSpeedMps = predX[3],
            trackDeg = GeoMath.normalizeDegrees360(Math.toDegrees(predX[4])),
            turnRateDegPerSec = Math.toDegrees(predX[5]),
            verticalRateMps = predX[6],
            horizontalSigmaMeters = totalSigma
        )
    }

    /**
     * Ingests a new ADS-B observation at measurementTimeSec.
     * Predicts from stateTimeSec to measurementTimeSec, then performs sequential scalar
     * Joseph-form EKF updates for every observed telemetry channel.
     */
    fun update(
        measuredEnu: EnuVector,
        groundSpeedMps: Double?,
        trackDeg: Double?,
        turnRateDegPerSec: Double?,
        verticalRateMps: Double?,
        measurementTimeSec: Double,
        isGeomAltitude: Boolean
    ) {
        val dt = measurementTimeSec - stateTimeSec
        if (dt < -1.5) {
            // Stale out-of-order packet older than current filtered state; ignore.
            return
        }
        if (dt > 0.0) {
            predictInPlace(dt.coerceAtMost(60.0))
        }

        // 1. Position update (East, North, Up)
        scalarUpdate(index = 0, z = measuredEnu.east, rVar = 22.0 * 22.0, isAngle = false)
        scalarUpdate(index = 1, z = measuredEnu.north, rVar = 22.0 * 22.0, isAngle = false)
        val altStd = if (isGeomAltitude) 25.0 else 45.0
        scalarUpdate(index = 2, z = measuredEnu.up, rVar = altStd * altStd, isAngle = false)

        // 2. Ground speed update
        if (groundSpeedMps != null && groundSpeedMps >= 0.0 && groundSpeedMps < 500.0) {
            scalarUpdate(index = 3, z = groundSpeedMps, rVar = 2.5 * 2.5, isAngle = false)
            x[3] = x[3].coerceIn(0.0, 500.0)
        }

        // 3. Heading / track update + inferred turn rate if telemetry omits track_rate
        if (trackDeg != null) {
            val trackRad = GeoMath.wrapAngleRad(Math.toRadians(trackDeg))
            scalarUpdate(index = 4, z = trackRad, rVar = 0.035 * 0.035, isAngle = true)
            x[4] = GeoMath.wrapAngleRad(x[4])

            if (turnRateDegPerSec == null && lastObservedTrackRad != null) {
                val trackDt = measurementTimeSec - lastObservedTrackTimeSec
                if (trackDt in 0.8..15.0) {
                    val dTrack = GeoMath.wrapAngleRad(trackRad - lastObservedTrackRad!!)
                    val inferredOmega = (dTrack / trackDt).coerceIn(-MAX_TURN_RATE_RAD_S, MAX_TURN_RATE_RAD_S)
                    // Higher measurement variance since finite-differenced heading is noisier than ADS-B track_rate
                    val sigmaInferred = 0.025 + 0.03 / trackDt
                    scalarUpdate(index = 5, z = inferredOmega, rVar = sigmaInferred * sigmaInferred, isAngle = false)
                    x[5] = x[5].coerceIn(-MAX_TURN_RATE_RAD_S, MAX_TURN_RATE_RAD_S)
                }
            }
            lastObservedTrackRad = trackRad
            lastObservedTrackTimeSec = measurementTimeSec
        }

        // 4. Direct ADS-B turn rate update (e.g. airplanes.live / adsb.lol track_rate)
        if (turnRateDegPerSec != null) {
            val omegaRad = Math.toRadians(turnRateDegPerSec).coerceIn(-MAX_TURN_RATE_RAD_S, MAX_TURN_RATE_RAD_S)
            scalarUpdate(index = 5, z = omegaRad, rVar = 0.008 * 0.008, isAngle = false)
            x[5] = x[5].coerceIn(-MAX_TURN_RATE_RAD_S, MAX_TURN_RATE_RAD_S)
        }

        // 5. Vertical rate update
        if (verticalRateMps != null && abs(verticalRateMps) < 80.0) {
            scalarUpdate(index = 6, z = verticalRateMps, rVar = 1.8 * 1.8, isAngle = false)
            x[6] = x[6].coerceIn(-60.0, 60.0)
        }

        updateCount++
    }

    /**
     * Shifts the filter's position origin (when observer moves significantly).
     */
    fun shiftOrigin(deltaEast: Double, deltaNorth: Double, deltaUp: Double) {
        x[0] -= deltaEast
        x[1] -= deltaNorth
        x[2] -= deltaUp
    }

    private fun propagateState(s: DoubleArray, dt: Double): DoubleArray {
        val out = s.copyOf()
        val v = s[3]
        val psi = s[4]
        val omega = s[5]
        val vz = s[6]

        if (abs(omega) > OMEGA_EPS) {
            val psiNew = psi + omega * dt
            out[0] = s[0] + (v / omega) * (cos(psi) - cos(psiNew))
            out[1] = s[1] + (v / omega) * (sin(psiNew) - sin(psi))
            out[4] = GeoMath.wrapAngleRad(psiNew)
        } else {
            out[0] = s[0] + v * sin(psi) * dt
            out[1] = s[1] + v * cos(psi) * dt
            out[4] = GeoMath.wrapAngleRad(psi + omega * dt)
        }

        out[2] = s[2] + vz * dt
        out[3] = v
        out[5] = if (dt > 0.0) omega * exp(-dt / TURN_RATE_DECAY_TAU_SEC) else omega
        out[6] = vz
        return out
    }

    private fun predictStep(dt: Double) {
        val v = x[3]
        val psi = x[4]
        val omega = x[5]
        val decay = exp(-dt / TURN_RATE_DECAY_TAU_SEC)

        // State transition Jacobian F = df/dx (7x7 row-major, initialized to Identity)
        val f = DoubleArray(N * N)
        for (i in 0 until N) f[i * N + i] = 1.0

        val sinPsi = sin(psi)
        val cosPsi = cos(psi)

        if (abs(omega) > OMEGA_EPS) {
            val psiNew = psi + omega * dt
            val sinNew = sin(psiNew)
            val cosNew = cos(psiNew)
            val invW = 1.0 / omega
            val invW2 = invW * invW

            // dpE / d(v, psi, omega)
            f[0 * N + 3] = invW * (cosPsi - cosNew)
            f[0 * N + 4] = v * invW * (-sinPsi + sinNew)
            f[0 * N + 5] = v * (dt * sinNew * invW - (cosPsi - cosNew) * invW2)

            // dpN / d(v, psi, omega)
            f[1 * N + 3] = invW * (sinNew - sinPsi)
            f[1 * N + 4] = v * invW * (cosNew - cosPsi)
            f[1 * N + 5] = v * (dt * cosNew * invW - (sinNew - sinPsi) * invW2)
        } else {
            // Straight-line limit (smooth at omega -> 0)
            f[0 * N + 3] = sinPsi * dt
            f[0 * N + 4] = v * cosPsi * dt
            f[0 * N + 5] = 0.5 * v * cosPsi * dt * dt

            f[1 * N + 3] = cosPsi * dt
            f[1 * N + 4] = -v * sinPsi * dt
            f[1 * N + 5] = -0.5 * v * sinPsi * dt * dt
        }

        // dpU / dvZ
        f[2 * N + 6] = dt
        // dpsi / domega
        f[4 * N + 5] = dt
        // domega / domega
        f[5 * N + 5] = decay

        // Propagate state x
        val nextX = propagateState(x, dt)
        nextX.copyInto(x)

        // Compute P_pred = F * P * F^T + Q
        val fp = matMul(f, p)
        val pNext = matMulTransposeB(fp, f)

        // Process noise Q (continuous white-noise acceleration & turn-acceleration model)
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2

        val sigmaA = 1.8            // horizontal acceleration noise (m/s^2)
        val sigmaAlpha = 0.014      // turn angular acceleration noise (rad/s^2)
        val sigmaAz = 0.8           // vertical acceleration noise (m/s^2)

        val qPos = 0.25 * dt4 * sigmaA * sigmaA + 4.0 * dt
        val qVel = dt2 * sigmaA * sigmaA
        val qPsi = 0.25 * dt4 * sigmaAlpha * sigmaAlpha + 1e-5 * dt
        val qOmega = dt2 * sigmaAlpha * sigmaAlpha
        val qUp = 0.25 * dt4 * sigmaAz * sigmaAz + 2.0 * dt
        val qVz = dt2 * sigmaAz * sigmaAz

        pNext[0 * N + 0] += qPos
        pNext[1 * N + 1] += qPos
        pNext[2 * N + 2] += qUp
        pNext[3 * N + 3] += qVel
        pNext[4 * N + 4] += qPsi
        pNext[5 * N + 5] += qOmega
        pNext[6 * N + 6] += qVz

        // Symmetrize P to prevent numerical drift
        for (i in 0 until N) {
            for (j in i + 1 until N) {
                val avg = 0.5 * (pNext[i * N + j] + pNext[j * N + i])
                pNext[i * N + j] = avg
                pNext[j * N + i] = avg
            }
        }
        pNext.copyInto(p)
    }

    /**
     * Sequential scalar EKF update for observing state component x[index] directly (H = e_index^T).
     * Uses Joseph-equivalent rank-1 stabilized update:
     *   S = P[i,i] + R
     *   K = P[:, i] / S
     *   x <- x + K * y
     *   P <- P - K * P[i, :] (symmetrized & floored on diagonal)
     */
    private fun scalarUpdate(index: Int, z: Double, rVar: Double, isAngle: Boolean) {
        val rawInnov = z - x[index]
        val y = if (isAngle) GeoMath.wrapAngleRad(rawInnov) else rawInnov

        val s = p[index * N + index] + rVar
        if (s <= 1e-12) return

        val k = DoubleArray(N) { row -> p[row * N + index] / s }

        // State update
        for (row in 0 until N) {
            x[row] += k[row] * y
        }
        x[4] = GeoMath.wrapAngleRad(x[4])

        // Joseph-form covariance update for H = e_index^T:
        // (I - K H) P (I - K H)^T + K R K^T
        val imkh = DoubleArray(N * N)
        for (i in 0 until N) imkh[i * N + i] = 1.0
        for (row in 0 until N) {
            imkh[row * N + index] -= k[row]
        }
        val temp = matMul(imkh, p)
        val pNew = matMulTransposeB(temp, imkh)
        for (row in 0 until N) {
            for (col in 0 until N) {
                pNew[row * N + col] += k[row] * rVar * k[col]
            }
        }
        // Symmetrize & enforce minimum positive variance on diagonal
        for (i in 0 until N) {
            if (pNew[i * N + i] < 1e-8) pNew[i * N + i] = 1e-8
            for (j in i + 1 until N) {
                val avg = 0.5 * (pNew[i * N + j] + pNew[j * N + i])
                pNew[i * N + j] = avg
                pNew[j * N + i] = avg
            }
        }
        pNew.copyInto(p)
    }

    private fun getP(r: Int, c: Int): Double = p[r * N + c]
    private fun setP(r: Int, c: Int, v: Double) {
        p[r * N + c] = v
    }

    private fun matMul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(N * N)
        for (i in 0 until N) {
            val iOff = i * N
            for (k in 0 until N) {
                val aik = a[iOff + k]
                if (aik == 0.0) continue
                val kOff = k * N
                for (j in 0 until N) {
                    out[iOff + j] += aik * b[kOff + j]
                }
            }
        }
        return out
    }

    private fun matMulTransposeB(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(N * N)
        for (i in 0 until N) {
            val iOff = i * N
            for (j in 0 until N) {
                val jOff = j * N
                var sum = 0.0
                for (k in 0 until N) {
                    sum += a[iOff + k] * b[jOff + k]
                }
                out[iOff + j] = sum
            }
        }
        return out
    }
}
