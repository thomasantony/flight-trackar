package com.flighttrackar.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flighttrackar.data.AdsbRepository
import com.flighttrackar.math.CameraProjection
import com.flighttrackar.math.GeoMath
import com.flighttrackar.model.AdsbProviderChoice
import com.flighttrackar.model.AppSettings
import com.flighttrackar.model.CalibrationOffsets
import com.flighttrackar.model.CameraIntrinsics
import com.flighttrackar.model.DeviceOrientation
import com.flighttrackar.model.ObserverLocation
import com.flighttrackar.model.ProjectedAircraft
import com.flighttrackar.model.SkyFrameStats
import com.flighttrackar.model.UnitSystem
import com.flighttrackar.model.VisionMode
import com.flighttrackar.model.VisualDetection
import com.flighttrackar.sensors.LocationTracker
import com.flighttrackar.sensors.OrientationSensorManager
import com.flighttrackar.tracking.FlightTrackManager
import com.flighttrackar.vision.SkyAircraftDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.tan

data class FlightArUiState(
    val observer: ObserverLocation? = null,
    val orientation: DeviceOrientation? = null,
    val intrinsics: CameraIntrinsics = CameraIntrinsics(),
    val settings: AppSettings = AppSettings(),
    val projectedAircraft: List<ProjectedAircraft> = emptyList(),
    val totalTrackedCount: Int = 0,
    val activeSourceName: String = "Connecting...",
    val lastFetchLatencyMs: Long = 0L,
    val feedError: String? = null,
    val skyStats: SkyFrameStats = SkyFrameStats(),
    val selectedIcao24: String? = null,
    val showCalibrationPanel: Boolean = false,
    val showSettingsSheet: Boolean = false,
    val requestedZoomRatio: Float? = null
) {
    val selectedAircraft: ProjectedAircraft?
        get() = selectedIcao24?.let { id -> projectedAircraft.firstOrNull { it.track.icao24 == id } }

    /** Best high-confidence visually locked aircraft available for one-tap calibration sync. */
    val bestCalibrationCandidate: ProjectedAircraft?
        get() = projectedAircraft
            .filter { it.isInViewport && (it.visualDetection?.confidence ?: 0f) >= 0.45f }
            .maxByOrNull { it.visualDetection?.confidence ?: 0f }

    val trueHeadingDeg: Float
        get() {
            val mag = orientation?.magneticHeadingDeg ?: 0f
            val decl = observer?.magneticDeclinationDeg ?: 0f
            return GeoMath.normalizeDegrees360((mag + decl - settings.calibration.azimuthOffsetDeg).toDouble()).toFloat()
        }

    val calibratedPitchDeg: Float
        get() = (orientation?.pitchDeg ?: 0f) - settings.calibration.pitchOffsetDeg
}

class FlightArViewModel(application: Application) : AndroidViewModel(application) {

    private val adsbRepository = AdsbRepository()
    private val trackManager = FlightTrackManager()
    val orientationManager = OrientationSensorManager(application)
    private val locationTracker = LocationTracker(application)

    private val _uiState = MutableStateFlow(FlightArUiState())
    val uiState: StateFlow<FlightArUiState> = _uiState.asStateFlow()

    /** Latest visual detections from the CameraX analyzer thread. */
    private val latestVisualDetections = AtomicReference<Map<String, VisualDetection>>(emptyMap())

    /** Latest ROI hints published by the 30Hz render loop for the CameraX analyzer thread. */
    private val latestRoiHints = AtomicReference<List<SkyAircraftDetector.AircraftRoiHint>>(emptyList())

    private var pollingJob: Job? = null
    private var renderLoopJob: Job? = null
    private var sensorsActive = false

    // Default fallback location (San Francisco Bay Area) ONLY used if Demo Simulation mode is selected before GPS locks
    private val demoFallbackObserver = ObserverLocation(
        latitudeDeg = 37.6213,
        longitudeDeg = -122.3790,
        altitudeMeters = 15.0,
        accuracyMeters = 10f,
        magneticDeclinationDeg = 13.0f
    )

    fun startTracking() {
        if (sensorsActive) return
        sensorsActive = true
        orientationManager.start()
        locationTracker.start()

        startAdsbPollingLoop()
        startProjectionLoop()
    }

    fun stopTracking() {
        if (!sensorsActive) return
        sensorsActive = false
        orientationManager.stop()
        locationTracker.stop()
        pollingJob?.cancel()
        renderLoopJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        stopTracking()
    }

    fun getLatestRoiHints(): List<SkyAircraftDetector.AircraftRoiHint> = latestRoiHints.get()

    fun getCurrentVisionMode(): VisionMode = _uiState.value.settings.visionMode

    fun getCurrentIntrinsics(): CameraIntrinsics = _uiState.value.intrinsics

    /**
     * Called from CameraX ImageAnalysis background executor with fresh CV results.
     */
    fun onVisionFrameAnalyzed(result: SkyAircraftDetector.FrameDetectionResult) {
        latestVisualDetections.set(result.detections)
        _uiState.update { it.copy(skyStats = result.stats) }

        // Optional gentle auto-trim when enabled and a high-confidence visual lock exists
        val state = _uiState.value
        if (state.settings.calibration.autoTrimFromVisualLock) {
            val best = state.bestCalibrationCandidate
            val det = best?.visualDetection
            if (best != null && det != null && det.confidence >= 0.72f) {
                val visX = det.centerXNorm * state.intrinsics.viewportWidthPx
                val visY = det.centerYNorm * state.intrinsics.viewportHeightPx
                val (dAz, dPitch) = CameraProjection.screenErrorToCalibrationDeltaDeg(
                    predictedX = best.predictedScreenX,
                    predictedY = best.predictedScreenY,
                    visualX = visX,
                    visualY = visY,
                    intrinsics = state.intrinsics,
                    deviceRollDeg = state.orientation?.rollDeg ?: 0f,
                    elevationDeg = best.elevationDeg.toFloat()
                )
                // Only apply gentle trim if error is reasonable (< 12 deg) so outliers never pull calibration
                if (hypot(dAz.toDouble(), dPitch.toDouble()) < 12.0) {
                    nudgeCalibration(deltaAzDeg = dAz * 0.04f, deltaPitchDeg = dPitch * 0.04f)
                }
            }
        }
    }

    fun updateDisplayRotation(rotation: Int) {
        orientationManager.displayRotation = rotation
    }

    fun updateCameraIntrinsics(transform: (CameraIntrinsics) -> CameraIntrinsics) {
        _uiState.update { state ->
            val updated = transform(state.intrinsics)
            orientationManager.setZoomRatio(updated.zoomRatio)
            state.copy(intrinsics = updated)
        }
    }

    fun requestZoomRatio(ratio: Float) {
        val intr = _uiState.value.intrinsics
        val clamped = ratio.coerceIn(intr.minZoomRatio, intr.maxZoomRatio)
        _uiState.update { it.copy(requestedZoomRatio = clamped) }
    }

    fun onZoomApplied(actualRatio: Float) {
        orientationManager.setZoomRatio(actualRatio)
        _uiState.update { state ->
            state.copy(
                intrinsics = state.intrinsics.copy(zoomRatio = actualRatio),
                requestedZoomRatio = null
            )
        }
    }

    fun selectAircraft(icao24: String?) {
        _uiState.update { it.copy(selectedIcao24 = icao24) }
    }

    /**
     * Hit-tests a screen tap against all projected aircraft reticles & visual bounding boxes.
     */
    fun onOverlayTap(tapX: Float, tapY: Float) {
        val state = _uiState.value
        val vw = state.intrinsics.viewportWidthPx.toFloat()
        val vh = state.intrinsics.viewportHeightPx.toFloat()
        var bestIcao: String? = null
        var bestDist = 110f // generous touch radius in px

        for (ac in state.projectedAircraft) {
            if (!ac.isInViewport) continue
            val dPred = hypot(tapX - ac.predictedScreenX, tapY - ac.predictedScreenY)
            if (dPred < bestDist) {
                bestDist = dPred
                bestIcao = ac.track.icao24
            }
            val det = ac.visualDetection
            if (det != null) {
                val vx = det.centerXNorm * vw
                val vy = det.centerYNorm * vh
                val dVis = hypot(tapX - vx, tapY - vy)
                if (dVis < bestDist) {
                    bestDist = dVis
                    bestIcao = ac.track.icao24
                }
            }
        }
        _uiState.update { it.copy(selectedIcao24 = bestIcao) }
    }

    fun toggleDebugHud() {
        _uiState.update { state ->
            state.copy(settings = state.settings.copy(showDebugHud = !state.settings.showDebugHud))
        }
    }

    fun toggleCalibrationPanel() {
        _uiState.update { it.copy(showCalibrationPanel = !it.showCalibrationPanel) }
    }

    fun setSettingsSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showSettingsSheet = visible) }
    }

    fun cycleVisionMode() {
        val order = listOf(VisionMode.AUTO, VisionMode.DAY, VisionMode.NIGHT, VisionMode.OFF)
        _uiState.update { state ->
            val next = order[(order.indexOf(state.settings.visionMode) + 1) % order.size]
            state.copy(settings = state.settings.copy(visionMode = next))
        }
    }

    fun updateProvider(provider: AdsbProviderChoice) {
        trackManager.clear()
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(provider = provider),
                feedError = null
            )
        }
    }

    fun updateVisionMode(mode: VisionMode) {
        _uiState.update { it.copy(settings = it.settings.copy(visionMode = mode)) }
    }

    fun updateUnitSystem(units: UnitSystem) {
        _uiState.update { it.copy(settings = it.settings.copy(unitSystem = units)) }
    }

    fun updateSearchRadiusNm(radiusNm: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(searchRadiusNm = radiusNm.coerceIn(10, 100))) }
    }

    fun updateExtraPredictionLeadSec(leadSec: Float) {
        _uiState.update { it.copy(settings = it.settings.copy(extraPredictionLeadSec = leadSec.coerceIn(0f, 15f))) }
    }

    fun nudgeCalibration(deltaAzDeg: Float, deltaPitchDeg: Float) {
        _uiState.update { state ->
            val cal = state.settings.calibration
            val newCal = cal.copy(
                azimuthOffsetDeg = (cal.azimuthOffsetDeg + deltaAzDeg).coerceIn(-45f, 45f),
                pitchOffsetDeg = (cal.pitchOffsetDeg + deltaPitchDeg).coerceIn(-30f, 30f)
            )
            state.copy(settings = state.settings.copy(calibration = newCal))
        }
    }

    fun resetCalibration() {
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(
                    calibration = CalibrationOffsets(autoTrimFromVisualLock = state.settings.calibration.autoTrimFromVisualLock)
                )
            )
        }
    }

    fun setAutoTrimFromVisualLock(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(
                    calibration = state.settings.calibration.copy(autoTrimFromVisualLock = enabled)
                )
            )
        }
    }

    /**
     * One-tap calibration sync: computes the angular error between the best visually locked
     * aircraft's camera bounding box and its EKF-predicted screen reticle, and snaps the
     * azimuth/pitch calibration offsets so all aircraft align accurately.
     */
    fun snapCalibrationToVisualLock() {
        val state = _uiState.value
        val candidate = state.bestCalibrationCandidate ?: return
        val det = candidate.visualDetection ?: return
        val visX = det.centerXNorm * state.intrinsics.viewportWidthPx
        val visY = det.centerYNorm * state.intrinsics.viewportHeightPx

        val (dAz, dPitch) = CameraProjection.screenErrorToCalibrationDeltaDeg(
            predictedX = candidate.predictedScreenX,
            predictedY = candidate.predictedScreenY,
            visualX = visX,
            visualY = visY,
            intrinsics = state.intrinsics,
            deviceRollDeg = state.orientation?.rollDeg ?: 0f,
            elevationDeg = candidate.elevationDeg.toFloat()
        )
        nudgeCalibration(deltaAzDeg = dAz, deltaPitchDeg = dPitch)
    }

    private fun startAdsbPollingLoop() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value
                val gpsLoc = locationTracker.location.value
                val observer = gpsLoc ?: if (state.settings.provider == AdsbProviderChoice.DEMO_SIM) {
                    demoFallbackObserver
                } else null

                if (observer != null) {
                    val nowMono = monotonicSec()
                    val result = adsbRepository.fetchNearbyAircraft(
                        observer = observer,
                        radiusNm = state.settings.searchRadiusNm,
                        providerChoice = state.settings.provider,
                        nowMonotonicSec = nowMono
                    )

                    if (result.observations.isNotEmpty() || result.errorMessage == null) {
                        trackManager.ingestObservations(result.observations, observer, nowMono)
                    }

                    _uiState.update {
                        it.copy(
                            activeSourceName = result.activeSourceName,
                            lastFetchLatencyMs = result.latencyMs,
                            feedError = result.errorMessage
                        )
                    }

                    val pollDelayMs = when (state.settings.provider) {
                        AdsbProviderChoice.OPENSKY -> 8000L
                        AdsbProviderChoice.DEMO_SIM -> 2000L
                        else -> if (result.activeSourceName == "OpenSky") 8000L else 2200L
                    }
                    delay(pollDelayMs)
                } else {
                    delay(800L)
                }
            }
        }
    }

    private fun startProjectionLoop() {
        renderLoopJob?.cancel()
        renderLoopJob = viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value
                val gpsLoc = locationTracker.location.value
                val observer = gpsLoc ?: if (state.settings.provider == AdsbProviderChoice.DEMO_SIM) {
                    demoFallbackObserver
                } else null
                val orient = orientationManager.orientation.value
                val intr = state.intrinsics
                val settings = state.settings

                if (observer != null) {
                    val nowMono = monotonicSec()
                    val tracks = trackManager.predictAll(
                        observer = observer,
                        nowMonotonicSec = nowMono,
                        extraLeadSec = settings.extraPredictionLeadSec.toDouble()
                    )
                    val visualMap = latestVisualDetections.get()
                    val projected = ArrayList<ProjectedAircraft>(tracks.size)
                    val hints = ArrayList<SkyAircraftDetector.AircraftRoiHint>()

                    val vw = intr.viewportWidthPx.coerceAtLeast(1).toFloat()
                    val vh = intr.viewportHeightPx.coerceAtLeast(1).toFloat()
                    val fPx = CameraProjection.effectiveFocalLengthPx(intr)

                    for (track in tracks) {
                        val azEl = GeoMath.enuToAzElRange(track.predictedEnu)
                        if (azEl.elevationDeg < settings.minElevationDeg) continue

                        val predProj = CameraProjection.projectEnuToScreen(
                            trueEnu = track.predictedEnu,
                            orientation = orient,
                            magneticDeclinationDeg = observer.magneticDeclinationDeg,
                            calibration = settings.calibration,
                            intrinsics = intr
                        )
                        if (!predProj.isInFront) continue

                        val rawProj = CameraProjection.projectEnuToScreen(
                            trueEnu = track.rawEnu,
                            orientation = orient,
                            magneticDeclinationDeg = observer.magneticDeclinationDeg,
                            calibration = settings.calibration,
                            intrinsics = intr
                        )

                        val futurePts = track.futureTrajectoryEnu.mapNotNull { enu ->
                            val fp = CameraProjection.projectEnuToScreen(
                                trueEnu = enu,
                                orientation = orient,
                                magneticDeclinationDeg = observer.magneticDeclinationDeg,
                                calibration = settings.calibration,
                                intrinsics = intr
                            )
                            if (fp.isInFront) fp.screenX to fp.screenY else null
                        }

                        // Compute projected screen velocity (px/s) using first future point (+3s)
                        val (vxPx, vyPx) = if (futurePts.isNotEmpty()) {
                            (futurePts[0].first - predProj.screenX) / 3f to
                                (futurePts[0].second - predProj.screenY) / 3f
                        } else 0f to 0f

                        val apparentSizePx = CameraProjection.expectedApparentSizePx(
                            physicalSizeMeters = 40.0,
                            slantRangeMeters = azEl.slantRangeMeters,
                            intrinsics = intr
                        )

                        val det = visualMap[track.icao24]?.takeIf {
                            System.currentTimeMillis() - it.timestampMs < 1500L
                        }

                        projected.add(
                            ProjectedAircraft(
                                track = track,
                                predictedScreenX = predProj.screenX,
                                predictedScreenY = predProj.screenY,
                                rawScreenX = if (rawProj.isInFront) rawProj.screenX else null,
                                rawScreenY = if (rawProj.isInFront) rawProj.screenY else null,
                                futureScreenPoints = futurePts,
                                screenVelocityXPxPerSec = vxPx,
                                screenVelocityYPxPerSec = vyPx,
                                azimuthDeg = azEl.azimuthDeg,
                                elevationDeg = azEl.elevationDeg,
                                slantRangeMeters = azEl.slantRangeMeters,
                                isInViewport = predProj.isInViewport,
                                expectedApparentSizePx = apparentSizePx,
                                visualDetection = det
                            )
                        )

                        // Build ROI hint for visual detector if within visible viewport and visual range (< 55 km)
                        if (predProj.isInViewport &&
                            predProj.screenX in 0f..vw &&
                            predProj.screenY in 0f..vh &&
                            azEl.slantRangeMeters < 55_000.0 &&
                            azEl.elevationDeg >= 2.5
                        ) {
                            // Angular uncertainty = compass uncertainty (~5.5 deg) + EKF position uncertainty
                            val ekfAngDeg = Math.toDegrees(track.positionSigmaMeters / azEl.slantRangeMeters.coerceAtLeast(100.0))
                            val searchHalfDeg = (5.5 + ekfAngDeg * 1.5).coerceIn(3.5, 11.0)
                            val halfPx = (fPx * tan(Math.toRadians(searchHalfDeg))).toFloat()
                            hints.add(
                                SkyAircraftDetector.AircraftRoiHint(
                                    icao24 = track.icao24,
                                    predictedXNorm = predProj.screenX / vw,
                                    predictedYNorm = predProj.screenY / vh,
                                    roiHalfWidthNorm = (halfPx / vw).coerceIn(0.06f, 0.28f),
                                    roiHalfHeightNorm = (halfPx / vh).coerceIn(0.05f, 0.24f),
                                    velocityXNormPerSec = vxPx / vw,
                                    velocityYNormPerSec = vyPx / vh,
                                    expectedSizeNorm = (apparentSizePx / vw).coerceIn(0.005f, 0.2f),
                                    elevationDeg = azEl.elevationDeg
                                )
                            )
                        }
                    }

                    // Sort closest aircraft first for clean overlapping priority and cap CV ROI hints to 8 closest
                    projected.sortBy { it.slantRangeMeters }
                    latestRoiHints.set(hints.take(8))

                    _uiState.update {
                        it.copy(
                            observer = observer,
                            orientation = orient,
                            projectedAircraft = projected,
                            totalTrackedCount = tracks.size
                        )
                    }
                } else {
                    _uiState.update { it.copy(orientation = orient) }
                }

                delay(33L) // ~30 Hz smooth AR projection updates
            }
        }
    }

    private fun monotonicSec(): Double = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
}
