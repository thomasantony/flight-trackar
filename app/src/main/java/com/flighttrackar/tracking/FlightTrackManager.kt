package com.flighttrackar.tracking

import com.flighttrackar.math.GeoMath
import com.flighttrackar.model.AdsbObservation
import com.flighttrackar.model.EnuVector
import com.flighttrackar.model.ObserverLocation
import com.flighttrackar.model.TrackedAircraft
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages a collection of active [CtrvEkfTracker] instances keyed by ICAO24 hex code.
 * Handles origin shifts when the observer moves, prunes stale tracks, and produces
 * 60Hz extrapolated snapshots (with optional extra future lead time) for AR projection.
 */
class FlightTrackManager {

    companion object {
        private const val TRACK_EXPIRY_SEC = 45.0
        private const val ORIGIN_RECENTER_THRESHOLD_M = 150.0
    }

    private data class TrackEntry(
        val tracker: CtrvEkfTracker,
        var latestObs: AdsbObservation,
        var rawEnu: EnuVector,
        var hasTelemetryTurnRate: Boolean,
        var lastReceivedTimeSec: Double
    )

    private val tracks = ConcurrentHashMap<String, TrackEntry>()
    @Volatile
    private var originLocation: ObserverLocation? = null

    /**
     * Ingests a fresh batch of ADS-B observations relative to the observer's current position.
     */
    @Synchronized
    fun ingestObservations(
        observations: List<AdsbObservation>,
        observer: ObserverLocation,
        nowMonotonicSec: Double
    ) {
        ensureOrigin(observer)
        val origin = originLocation ?: return

        for (obs in observations) {
            if (obs.onGround) continue
            val altM = obs.bestAltitudeMeters ?: continue

            val enu = GeoMath.geodeticToEnu(
                observerLatDeg = origin.latitudeDeg,
                observerLonDeg = origin.longitudeDeg,
                observerAltMeters = origin.altitudeMeters,
                targetLatDeg = obs.latitudeDeg,
                targetLonDeg = obs.longitudeDeg,
                targetAltMeters = altM
            )

            val existing = tracks[obs.icao24]
            if (existing == null) {
                val initTrackRad = Math.toRadians(obs.trackDeg ?: 0.0)
                val initTurnRad = Math.toRadians(obs.turnRateDegPerSec ?: 0.0)
                val tracker = CtrvEkfTracker(
                    initialEnu = enu,
                    initialGroundSpeedMps = obs.groundSpeedMps ?: 0.0,
                    initialTrackRad = initTrackRad,
                    initialTurnRateRadPerSec = initTurnRad,
                    initialVerticalRateMps = obs.verticalRateMps ?: 0.0,
                    initialTimestampSec = obs.measurementTimeSec
                )
                tracks[obs.icao24] = TrackEntry(
                    tracker = tracker,
                    latestObs = obs,
                    rawEnu = enu,
                    hasTelemetryTurnRate = obs.turnRateDegPerSec != null,
                    lastReceivedTimeSec = nowMonotonicSec
                )
            } else {
                existing.tracker.update(
                    measuredEnu = enu,
                    groundSpeedMps = obs.groundSpeedMps,
                    trackDeg = obs.trackDeg,
                    turnRateDegPerSec = obs.turnRateDegPerSec,
                    verticalRateMps = obs.verticalRateMps,
                    measurementTimeSec = obs.measurementTimeSec,
                    isGeomAltitude = obs.geomAltitudeMeters != null
                )
                existing.latestObs = obs
                existing.rawEnu = enu
                existing.hasTelemetryTurnRate = obs.turnRateDegPerSec != null
                existing.lastReceivedTimeSec = nowMonotonicSec
            }
        }

        // Expire old tracks that haven't been seen recently
        val iter = tracks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (nowMonotonicSec - entry.lastReceivedTimeSec > TRACK_EXPIRY_SEC) {
                iter.remove()
            }
        }
    }

    /**
     * Generates real-time predicted snapshots for all active aircraft at [nowMonotonicSec]
     * plus optional [extraLeadSec] (for visualizing X seconds in the future).
     */
    @Synchronized
    fun predictAll(
        observer: ObserverLocation,
        nowMonotonicSec: Double,
        extraLeadSec: Double = 0.0
    ): List<TrackedAircraft> {
        val origin = originLocation ?: return emptyList()

        // Small offset between current observer GPS and the tracker's ENU origin
        val obsOffsetFromOrigin = GeoMath.geodeticToEnu(
            observerLatDeg = origin.latitudeDeg,
            observerLonDeg = origin.longitudeDeg,
            observerAltMeters = origin.altitudeMeters,
            targetLatDeg = observer.latitudeDeg,
            targetLonDeg = observer.longitudeDeg,
            targetAltMeters = observer.altitudeMeters
        )

        val targetTimeSec = nowMonotonicSec + extraLeadSec
        val result = ArrayList<TrackedAircraft>(tracks.size)

        for ((icao, entry) in tracks) {
            if (nowMonotonicSec - entry.lastReceivedTimeSec > TRACK_EXPIRY_SEC) continue

            val pred = entry.tracker.predictStateAt(targetTimeSec)
            val relPredEnu = EnuVector(
                east = pred.enu.east - obsOffsetFromOrigin.east,
                north = pred.enu.north - obsOffsetFromOrigin.north,
                up = pred.enu.up - obsOffsetFromOrigin.up
            )
            val relRawEnu = EnuVector(
                east = entry.rawEnu.east - obsOffsetFromOrigin.east,
                north = entry.rawEnu.north - obsOffsetFromOrigin.north,
                up = entry.rawEnu.up - obsOffsetFromOrigin.up
            )

            // Short future trajectory points (+3s, +6s, +9s beyond targetTimeSec) for HUD / Debug
            val futurePoints = listOf(3.0, 6.0, 9.0).map { step ->
                val fp = entry.tracker.predictStateAt(targetTimeSec + step).enu
                EnuVector(
                    east = fp.east - obsOffsetFromOrigin.east,
                    north = fp.north - obsOffsetFromOrigin.north,
                    up = fp.up - obsOffsetFromOrigin.up
                )
            }

            val obs = entry.latestObs
            val predictedAltM = observer.altitudeMeters + relPredEnu.up
            val totalLeadSec = (targetTimeSec - obs.measurementTimeSec).coerceAtLeast(0.0)
            val currentAgeSec = (nowMonotonicSec - obs.measurementTimeSec).coerceAtLeast(0.0)

            result.add(
                TrackedAircraft(
                    icao24 = icao,
                    callsign = obs.callsign,
                    registration = obs.registration,
                    aircraftType = obs.aircraftType,
                    description = obs.description,
                    squawk = obs.squawk,
                    sourceName = obs.sourceName,
                    rawEnu = relRawEnu,
                    predictedEnu = relPredEnu,
                    futureTrajectoryEnu = futurePoints,
                    groundSpeedMps = pred.groundSpeedMps,
                    trackDeg = pred.trackDeg,
                    turnRateDegPerSec = pred.turnRateDegPerSec,
                    verticalRateMps = pred.verticalRateMps,
                    hasTelemetryTurnRate = entry.hasTelemetryTurnRate,
                    altitudeMeters = predictedAltM,
                    baroAltitudeMeters = obs.baroAltitudeMeters,
                    geomAltitudeMeters = obs.geomAltitudeMeters,
                    totalPredictionLeadSec = totalLeadSec,
                    dataAgeSec = currentAgeSec,
                    positionSigmaMeters = pred.horizontalSigmaMeters,
                    updateCount = entry.tracker.updateCount
                )
            )
        }
        return result
    }

    @Synchronized
    fun clear() {
        tracks.clear()
        originLocation = null
    }

    private fun ensureOrigin(observer: ObserverLocation) {
        val current = originLocation
        if (current == null) {
            originLocation = observer
            return
        }
        val delta = GeoMath.geodeticToEnu(
            observerLatDeg = current.latitudeDeg,
            observerLonDeg = current.longitudeDeg,
            observerAltMeters = current.altitudeMeters,
            targetLatDeg = observer.latitudeDeg,
            targetLonDeg = observer.longitudeDeg,
            targetAltMeters = observer.altitudeMeters
        )
        if (delta.slantRangeMeters > ORIGIN_RECENTER_THRESHOLD_M) {
            for (entry in tracks.values) {
                entry.tracker.shiftOrigin(delta.east, delta.north, delta.up)
                entry.rawEnu = EnuVector(
                    entry.rawEnu.east - delta.east,
                    entry.rawEnu.north - delta.north,
                    entry.rawEnu.up - delta.up
                )
            }
            originLocation = observer
        }
    }
}
