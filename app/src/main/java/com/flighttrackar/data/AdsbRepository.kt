package com.flighttrackar.data

import com.flighttrackar.math.GeoMath
import com.flighttrackar.model.AdsbObservation
import com.flighttrackar.model.AdsbProviderChoice
import com.flighttrackar.model.ObserverLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AdsbFetchResult(
    val observations: List<AdsbObservation>,
    val activeSourceName: String,
    val latencyMs: Long,
    val errorMessage: String? = null
)

/**
 * Multi-provider open ADS-B repository supporting:
 *  - airplanes.live (v2/point, fast ~2s updates, includes track_rate & seen_pos)
 *  - adsb.lol (v2/point, identical readsb schema, automatic fallback)
 *  - OpenSky Network (/api/states/all, bounding box, final fallback)
 *  - Built-in Demo Simulation for offline/indoor testing
 */
class AdsbRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val simulator: SimulatedAdsbProvider = SimulatedAdsbProvider()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchNearbyAircraft(
        observer: ObserverLocation,
        radiusNm: Int,
        providerChoice: AdsbProviderChoice,
        nowMonotonicSec: Double,
        nowEpochSec: Double = System.currentTimeMillis() / 1000.0
    ): AdsbFetchResult = withContext(Dispatchers.IO) {
        val clampedRadius = radiusNm.coerceIn(5, 150)

        if (providerChoice == AdsbProviderChoice.DEMO_SIM) {
            val simObs = simulator.generateObservations(observer, nowMonotonicSec)
            return@withContext AdsbFetchResult(
                observations = simObs,
                activeSourceName = "Demo Sim",
                latencyMs = 1L
            )
        }

        val providersToTry: List<AdsbProviderChoice> = when (providerChoice) {
            AdsbProviderChoice.AUTO -> listOf(
                AdsbProviderChoice.AIRPLANES_LIVE,
                AdsbProviderChoice.ADSB_LOL,
                AdsbProviderChoice.OPENSKY
            )
            else -> listOf(providerChoice)
        }

        var lastError: String? = null
        for (provider in providersToTry) {
            val startMs = System.currentTimeMillis()
            try {
                val (url, sourceName) = buildRequestUrl(provider, observer, clampedRadius)
                val body = executeGet(url)
                val elapsed = System.currentTimeMillis() - startMs

                val parsed = when (provider) {
                    AdsbProviderChoice.AIRPLANES_LIVE, AdsbProviderChoice.ADSB_LOL ->
                        parseReadsbV2Json(body, sourceName, nowMonotonicSec)
                    AdsbProviderChoice.OPENSKY ->
                        parseOpenSkyJson(body, nowMonotonicSec, nowEpochSec)
                    else -> emptyList()
                }
                return@withContext AdsbFetchResult(
                    observations = parsed,
                    activeSourceName = sourceName,
                    latencyMs = elapsed
                )
            } catch (e: Exception) {
                lastError = "${provider.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        AdsbFetchResult(
            observations = emptyList(),
            activeSourceName = providerChoice.label,
            latencyMs = 0L,
            errorMessage = lastError ?: "Failed to fetch ADS-B data"
        )
    }

    private fun buildRequestUrl(
        provider: AdsbProviderChoice,
        observer: ObserverLocation,
        radiusNm: Int
    ): Pair<String, String> {
        val latStr = String.format(Locale.US, "%.4f", observer.latitudeDeg)
        val lonStr = String.format(Locale.US, "%.4f", observer.longitudeDeg)
        return when (provider) {
            AdsbProviderChoice.AIRPLANES_LIVE ->
                "https://api.airplanes.live/v2/point/$latStr/$lonStr/$radiusNm" to "airplanes.live"
            AdsbProviderChoice.ADSB_LOL ->
                "https://api.adsb.lol/v2/point/$latStr/$lonStr/$radiusNm" to "adsb.lol"
            AdsbProviderChoice.OPENSKY -> {
                val bbox = GeoMath.boundingBoxDeg(observer.latitudeDeg, observer.longitudeDeg, radiusNm.toDouble())
                val url = String.format(
                    Locale.US,
                    "https://opensky-network.org/api/states/all?lamin=%.4f&lomin=%.4f&lamax=%.4f&lomax=%.4f",
                    bbox[0], bbox[1], bbox[2], bbox[3]
                )
                url to "OpenSky"
            }
            else -> "" to provider.label
        }
    }

    private fun executeGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "FlightTrackAR/1.0 (Android AR Flight Tracker)")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("Empty response body")
        }
    }

    /**
     * Parses readsb / tar1090 v2 JSON used by airplanes.live and adsb.lol.
     */
    fun parseReadsbV2Json(
        rawJson: String,
        sourceName: String,
        nowMonotonicSec: Double
    ): List<AdsbObservation> {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val acArray = root["ac"]?.jsonArray ?: return emptyList()
        val result = ArrayList<AdsbObservation>(acArray.size)

        for (elem in acArray) {
            val obj = elem as? JsonObject ?: continue
            val hex = obj.str("hex")?.lowercase(Locale.US) ?: continue
            val lat = obj.dbl("lat") ?: continue
            val lon = obj.dbl("lon") ?: continue

            val altBaroEl = obj["alt_baro"]
            val isGround = altBaroEl.strOrNull()?.equals("ground", ignoreCase = true) == true
            val altBaroFt = altBaroEl.dblOrNull()
            val altGeomFt = obj.dbl("alt_geom")

            val gsKt = obj.dbl("gs")
            val track = obj.dbl("track")
            val trackRate = obj.dbl("track_rate")
            val vertRateFpm = obj.dbl("geom_rate") ?: obj.dbl("baro_rate")
            val seenPos = (obj.dbl("seen_pos") ?: obj.dbl("seen") ?: 0.0).coerceIn(0.0, 120.0)

            result.add(
                AdsbObservation(
                    icao24 = hex,
                    callsign = obj.str("flight")?.trim()?.takeIf { it.isNotEmpty() },
                    registration = obj.str("r")?.trim()?.takeIf { it.isNotEmpty() },
                    aircraftType = obj.str("t")?.trim()?.takeIf { it.isNotEmpty() },
                    description = obj.str("desc")?.trim()?.takeIf { it.isNotEmpty() },
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    geomAltitudeMeters = altGeomFt?.times(GeoMath.FEET_TO_METERS),
                    baroAltitudeMeters = altBaroFt?.times(GeoMath.FEET_TO_METERS),
                    groundSpeedMps = gsKt?.times(GeoMath.KNOTS_TO_MPS),
                    trackDeg = track,
                    turnRateDegPerSec = trackRate,
                    verticalRateMps = vertRateFpm?.times(GeoMath.FPM_TO_MPS),
                    squawk = obj.str("squawk")?.trim()?.takeIf { it.isNotEmpty() },
                    onGround = isGround,
                    positionAgeSec = seenPos,
                    measurementTimeSec = nowMonotonicSec - seenPos,
                    sourceName = sourceName
                )
            )
        }
        return result
    }

    /**
     * Parses OpenSky Network /api/states/all JSON response.
     */
    fun parseOpenSkyJson(
        rawJson: String,
        nowMonotonicSec: Double,
        nowEpochSec: Double
    ): List<AdsbObservation> {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val states = root["states"] as? JsonArray ?: return emptyList()
        val result = ArrayList<AdsbObservation>(states.size)

        for (elem in states) {
            val row = elem as? JsonArray ?: continue
            if (row.size < 14) continue

            val icao24 = row[0].strOrNull()?.lowercase(Locale.US) ?: continue
            val callsign = row[1].strOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val timePos = row[3].longOrNull() ?: row[4].longOrNull()
            val lon = row[5].dblOrNull() ?: continue
            val lat = row[6].dblOrNull() ?: continue
            val baroAltM = row[7].dblOrNull()
            val onGround = row[8].boolOrNull() ?: false
            val velocityMps = row[9].dblOrNull()
            val trueTrackDeg = row[10].dblOrNull()
            val vertRateMps = row[11].dblOrNull()
            val geoAltM = row[13].dblOrNull()
            val squawk = if (row.size > 14) row[14].strOrNull()?.trim()?.takeIf { it.isNotEmpty() } else null

            val ageSec = if (timePos != null) {
                (nowEpochSec - timePos.toDouble()).coerceIn(0.0, 120.0)
            } else {
                2.0
            }

            result.add(
                AdsbObservation(
                    icao24 = icao24,
                    callsign = callsign,
                    registration = null,
                    aircraftType = null,
                    description = null,
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    geomAltitudeMeters = geoAltM,
                    baroAltitudeMeters = baroAltM,
                    groundSpeedMps = velocityMps,
                    trackDeg = trueTrackDeg,
                    turnRateDegPerSec = null, // OpenSky does not report turn rate; EKF infers it
                    verticalRateMps = vertRateMps,
                    squawk = squawk,
                    onGround = onGround,
                    positionAgeSec = ageSec,
                    measurementTimeSec = nowMonotonicSec - ageSec,
                    sourceName = "OpenSky"
                )
            )
        }
        return result
    }

    private fun JsonObject.str(key: String): String? = this[key].strOrNull()
    private fun JsonObject.dbl(key: String): Double? = this[key].dblOrNull()

    private fun JsonElement?.strOrNull(): String? {
        val prim = this as? JsonPrimitive ?: return null
        return if (prim.isString) prim.content else null
    }

    private fun JsonElement?.dblOrNull(): Double? {
        val prim = this as? JsonPrimitive ?: return null
        return prim.doubleOrNull
    }

    private fun JsonElement?.longOrNull(): Long? {
        val prim = this as? JsonPrimitive ?: return null
        return prim.longOrNull
    }

    private fun JsonElement?.boolOrNull(): Boolean? {
        val prim = this as? JsonPrimitive ?: return null
        return prim.booleanOrNull
    }
}
