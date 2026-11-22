package com.flighttrackar.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.flighttrackar.math.GeoMath
import com.flighttrackar.model.DeviceOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Streams hardware-fused 3D device orientation using [Sensor.TYPE_ROTATION_VECTOR]
 * (accelerometer + gyroscope + magnetometer) with display-rotation remapping and
 * adaptive quaternion SLERP smoothing (extra steady when zoomed in, fast when panning).
 */
class OrientationSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val _orientation = MutableStateFlow(
        DeviceOrientation(
            rotationMatrix = floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            ),
            magneticHeadingDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 0f,
            accuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW
        )
    )
    val orientation: StateFlow<DeviceOrientation> = _orientation.asStateFlow()

    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    @Volatile
    private var currentZoomRatio: Float = 1.0f

    private var smoothedQuat: FloatArray? = null
    private val rawMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private var lastAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

    fun setZoomRatio(zoomRatio: Float) {
        currentZoomRatio = zoomRatio.coerceAtLeast(0.5f)
    }

    fun start() {
        val sensor = rotationSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        smoothedQuat = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        ) return

        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, event.values)

        // Adaptive low-pass filter on unit quaternion:
        // smoother when holding steady or zoomed in (e.g. 5x tele on Pixel 9 Pro),
        // snappier when panning rapidly so labels never lag behind the sky.
        val prev = smoothedQuat
        val filtered = if (prev == null) {
            q
        } else {
            // Ensure shortest-path hemisphere (dot >= 0)
            var dot = prev[0] * q[0] + prev[1] * q[1] + prev[2] * q[2] + prev[3] * q[3]
            if (dot < 0f) {
                q[0] = -q[0]; q[1] = -q[1]; q[2] = -q[2]; q[3] = -q[3]
                dot = -dot
            }
            val angularDiff = (1f - dot.coerceIn(0f, 1f))
            // Base alpha tightens slightly at high zoom, loosens quickly during rapid pans
            val baseAlpha = (0.30f / sqrt(currentZoomRatio.coerceIn(1f, 8f))).coerceIn(0.10f, 0.32f)
            val alpha = if (angularDiff > 0.002f) 0.65f else baseAlpha
            val out = FloatArray(4) { i -> prev[i] * (1f - alpha) + q[i] * alpha }
            val norm = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
            if (norm > 1e-6f) {
                for (i in 0..3) out[i] /= norm
            }
            out
        }
        smoothedQuat = filtered

        // Convert smoothed quaternion [w, x, y, z] back to SensorManager rotation vector [x, y, z, w]
        val rotVec = floatArrayOf(filtered[1], filtered[2], filtered[3], filtered[0])
        SensorManager.getRotationMatrixFromVector(rawMatrix, rotVec)

        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rawMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rawMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rawMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix
            )
            else -> rawMatrix.copyInto(remappedMatrix)
        }

        // Camera optical axis in device frame is (0, 0, -1).
        // World vector of camera forward = R * (0, 0, -1) = (-R[2], -R[5], -R[8]).
        val fwdE = -remappedMatrix[2].toDouble()
        val fwdN = -remappedMatrix[5].toDouble()
        val fwdU = -remappedMatrix[8].toDouble()

        val magHeadingDeg = GeoMath.normalizeDegrees360(Math.toDegrees(atan2(fwdE, fwdN))).toFloat()
        val pitchDeg = Math.toDegrees(asin(fwdU.coerceIn(-1.0, 1.0))).toFloat()

        // Device +X (screen right) in world frame is (R[0], R[3], R[6]); roll relative to horizon:
        val rightU = remappedMatrix[6].toDouble()
        val upU = remappedMatrix[7].toDouble()
        val rollDeg = Math.toDegrees(atan2(-rightU, hypot(upU, fwdU).coerceAtLeast(1e-4))).toFloat()

        _orientation.value = DeviceOrientation(
            rotationMatrix = remappedMatrix.copyOf(),
            magneticHeadingDeg = magHeadingDeg,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            accuracy = lastAccuracy,
            timestampNs = event.timestamp
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        lastAccuracy = accuracy
    }
}
