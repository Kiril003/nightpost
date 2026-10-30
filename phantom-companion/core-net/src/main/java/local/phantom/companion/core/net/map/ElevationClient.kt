package local.phantom.companion.core.net.map

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.geosrc.valueOrNull

/**
 * NAV-1 — keyless elevation lookup via open-elevation.com. POSTs the
 * sampled route points and returns metres above sea level in the same
 * order.
 *
 * Джерело хронічно перевантажене, і до ATLAS-0.1 клієнт не мав жодного
 * таймаута — картка маршруту могла висіти доти, доки сокет не помре сам.
 * Тепер бюджет часу жорсткий, а відмова типізована: картка каже «висота:
 * даних немає», ніколи не малює рівний фейковий профіль (закон чесності).
 */
class ElevationClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
) {
    private val client: HttpClient = HttpClient {
        install(UserAgent) { agent = OverpassMirrors.USER_AGENT }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        expectSuccess = false
    }

    /** Метри над рівнем моря в порядку [points], або `null` за будь-якої відмови. */
    suspend fun lookup(points: List<Pair<Double, Double>>): List<Double>? =
        lookupResult(points).valueOrNull()

    /** Те саме з причиною відмови — щоб «джерело мовчить» не читалось як «висоти немає». */
    suspend fun lookupResult(points: List<Pair<Double, Double>>): GeoResult<List<Double>> {
        if (points.isEmpty()) return GeoResult.Ok(emptyList())
        val body = try {
            val response = client.post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ElevationRequest.serializer(), ElevationRequest(
                    points.map { ElevationLocation(it.first, it.second) }
                )))
            }
            val code = response.status.value
            when {
                code == 429 -> return unavailable(SourceUnavailable.RateLimited(RETRY_AFTER_MS))
                code !in 200..299 -> return unavailable(SourceUnavailable.HttpError(code))
                else -> response.bodyAsText()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return unavailable(
                SourceUnavailable.Transport("${t.javaClass.simpleName}: ${t.message ?: "без деталей"}")
            )
        }
        if (body.isBlank()) return unavailable(SourceUnavailable.EmptyPayload)
        val parsed = try {
            json.decodeFromString(ElevationResponse.serializer(), body).results.map { it.elevation }
        } catch (t: Throwable) {
            return unavailable(
                SourceUnavailable.Malformed("${t.javaClass.simpleName}: ${t.message?.take(160) ?: "без деталей"}")
            )
        }
        // Профіль зі зсунутими індексами гірший за відсутній: він намалював
        // би підйом там, де спуск.
        if (parsed.size != points.size) {
            return unavailable(SourceUnavailable.Malformed("точок ${points.size}, висот ${parsed.size}"))
        }
        return GeoResult.Ok(parsed)
    }

    private fun unavailable(reason: SourceUnavailable): GeoResult<List<Double>> {
        Log.w(TAG, "elevation НЕДОСТУПНА (не «нуль метрів»): ${reason.detail}")
        return GeoResult.Unavailable(reason)
    }

    private companion object {
        const val TAG = "ElevationClient"
        const val DEFAULT_ENDPOINT = "https://api.open-elevation.com/api/v1/lookup"
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val RETRY_AFTER_MS = 60_000L

        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class ElevationRequest(val locations: List<ElevationLocation>)

@Serializable
private data class ElevationLocation(val latitude: Double, val longitude: Double)

@Serializable
private data class ElevationResponse(val results: List<ElevationPoint> = emptyList())

@Serializable
private data class ElevationPoint(val elevation: Double = 0.0)
