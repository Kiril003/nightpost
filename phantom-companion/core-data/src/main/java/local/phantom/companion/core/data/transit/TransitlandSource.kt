package local.phantom.companion.core.data.transit

import local.phantom.companion.core.net.geosrc.GeoBBox
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.transit.TransitDeparture
import local.phantom.companion.core.net.transit.TransitOperator
import local.phantom.companion.core.net.transit.TransitRoute
import local.phantom.companion.core.net.transit.TransitStop
import local.phantom.companion.core.net.transit.TransitlandClient

/**
 * Глобальний ярус розкладів — Transitland як джерело зупинок, ліній і табло
 * відправлень скрізь, де в нас немає власного фіда.
 *
 * ЩО ЦЕ НЕ Є: джерело позицій транспорту. Transitland v2 REST віддає
 * статичний GTFS; рухома мітка на склі з цих даних була б вигадкою
 * (польовий вердикт 13 — «за всю дорогу жодного транспорту зі значків не
 * існувало в реальності»).
 *
 * Ключ приходить лише з конфігурації збірки. Порожній ключ — це чесна
 * ВІДСУТНІСТЬ: [available] false, кожен запит повертає
 * [SourceUnavailable.MissingApiKey], HTTP-клієнт навіть не створюється.
 */
class TransitlandSource(
    apiKey: String?,
    private val defaultRadiusMeters: Double = DEFAULT_RADIUS_M,
    clientFactory: (String) -> TransitlandClient = TransitlandClient::withKey,
) {
    private val key: String? = apiKey?.trim()?.takeIf { it.isNotEmpty() }

    /** Чи джерело взагалі налаштоване. Реєстр читає це до першого запиту. */
    val available: Boolean get() = key != null

    // Лінива: без ключа жодного OkHttp-клієнта в памʼяті бути не повинно.
    private val clientLazy: Lazy<TransitlandClient?> =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) { key?.let(clientFactory) }

    private val client: TransitlandClient? get() = clientLazy.value

    /** Зупинки навколо точки. */
    suspend fun stopsAround(
        lat: Double,
        lon: Double,
        radiusMeters: Double = defaultRadiusMeters,
        limit: Int = 100,
    ): GeoResult<List<TransitStop>> {
        val c = client ?: return missingKey()
        val bbox = boxAround(lat, lon, radiusMeters) ?: return badPoint()
        return c.stopsInBbox(bbox, limit)
    }

    /** Лінії, що зупиняються на зупинці. */
    suspend fun linesAtStop(stopOnestopId: String): GeoResult<List<TransitRoute>> {
        val c = client ?: return missingKey()
        return c.routesThroughStop(stopOnestopId)
    }

    /** Табло відправлень від зупинки — розклад, не позиції. */
    suspend fun departuresAtStop(
        stopOnestopId: String,
        limit: Int = 20,
    ): GeoResult<List<TransitDeparture>> {
        val c = client ?: return missingKey()
        return c.departuresAtStop(stopOnestopId, limit)
    }

    /** Перевізники регіону — заголовок хабу за кордоном. */
    suspend fun operatorsAround(
        lat: Double,
        lon: Double,
        radiusMeters: Double = defaultRadiusMeters,
        limit: Int = 20,
    ): GeoResult<List<TransitOperator>> {
        val c = client ?: return missingKey()
        val bbox = boxAround(lat, lon, radiusMeters) ?: return badPoint()
        return c.operatorsInBbox(bbox, limit)
    }

    /** Закриває HTTP-клієнт, якщо він узагалі створювався. */
    fun close() {
        if (clientLazy.isInitialized()) clientLazy.value?.close()
    }

    private fun boxAround(lat: Double, lon: Double, radiusMeters: Double): GeoBBox? =
        GeoBBox.around(lat, lon, radiusMeters.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M))

    private fun missingKey(): GeoResult.Unavailable =
        GeoResult.Unavailable(SourceUnavailable.MissingApiKey)

    private fun badPoint(): GeoResult.Unavailable =
        GeoResult.Unavailable(SourceUnavailable.RequestRejected("непридатна точка"))

    companion object {
        /** Пішохідна досяжність зупинки — стільки й питаємо за замовчуванням. */
        const val DEFAULT_RADIUS_M: Double = 1_200.0

        const val MIN_RADIUS_M: Double = 100.0

        /** Стеля під ліміт площі самого клієнта (`MAX_BBOX_SQ_DEG`). */
        const val MAX_RADIUS_M: Double = 25_000.0
    }
}
