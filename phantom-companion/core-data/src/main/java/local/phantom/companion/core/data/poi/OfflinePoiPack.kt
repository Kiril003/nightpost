package local.phantom.companion.core.data.poi

import kotlin.math.PI
import kotlin.math.cos
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.map.TagSelector

/**
 * The bundled POI corpus — icons that need no network.
 *
 * The map draws its pins from a live Overpass call. On a jammed, roaming or
 * dead-zone network that call returns nothing and the map goes iconless — the
 * operator's recurring «where are the icons?». This is the offline-first floor
 * under that: a one-time OSM extract of the same categories the app queries
 * live (`scripts/fetch_kyiv_poi.py` → the active country pack's POI asset),
 * filtered to the viewport, so stations, banks, pharmacies and shops render
 * with ZERO network. No pack, or a pack without the extract — no floor:
 * abroad the map draws what Overpass answered and nothing beyond it.
 *
 * It is the twin of the transit surface bundle ([local.phantom.companion.core
 * .data.transit.surface.SurfaceFeed]): topology from OpenStreetMap, baked at
 * build time, honest about staleness (a POI can close; the pin is a lead, not
 * a promise — the dossier still confirms live when it can).
 */
@Serializable
private data class PackedPoi(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val name: String,
    val cat: String,
    val sub: String? = null,
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
private data class PoiPackDoc(
    val generated: Long = 0,
    val bbox: List<Double> = emptyList(),
    val count: Int = 0,
    /** Скільки сертифікованих сховищ піч НАРАХУВАЛА. `null` = пакет старий і не рахував. */
    @SerialName("certified_shelters") val certifiedShelters: Int? = null,
    val pois: List<PackedPoi> = emptyList(),
)

class OfflinePoiPack private constructor(
    private val pois: List<PackedPoi>,
    /** Bake epoch — the seed marker, so a re-baked pack re-seeds exactly once. */
    val generated: Long = 0,
    /**
     * Межі, у яких пакет узагалі щось знає: `[minLat, minLon, maxLat, maxLon]`.
     * Порожній список = меж не оголошено.
     *
     * Пакет ніс це число з першого дня, а [parse] його викидав. Наслідок був
     * тихий і дорогий: за межами покриття пошук відповідав «нічого не
     * знайдено» — тобто робив ВИСНОВОК ПРО МІСЦЕ там, де насправді просто не
     * дивився. Це два різні світи для людини: у першому їхати далі марно, у
     * другому — треба завантажити пакет.
     *
     * Заміряно 31 серпня 2026 на асеті, що їде в застосунку: 21 335 місць,
     * bbox `[50.21, 30.24, 50.59, 30.83]` — **це Київ**, 21 333 з 21 335
     * усередині міста. Шлях `country/ua/` називає ВЛАСНИКА пакета, а не
     * охоплення: за межами Києва довідник має нуль, і сказати про це може
     * лише це поле.
     */
    val bbox: List<Double> = emptyList(),
    /**
     * Скільки сертифікованих сховищ пакет нарахував під час випікання.
     * `null` — не рахував; це не те саме, що нуль, і обидва означають, що
     * пакет не має права робити заяв про місто.
     */
    val certifiedShelters: Int? = null,
) {

    val size: Int get() = pois.size

    /**
     * Чи взагалі дивився пакет у цю точку.
     *
     * `true`, коли меж не оголошено: старі пакети нічого про себе не казали, і
     * мовчання не має ставати відмовою — інакше ліки вимкнули б пошук там, де
     * він працював.
     */
    fun covers(lat: Double, lon: Double): Boolean {
        if (bbox.size < 4) return true
        return lat >= bbox[0] && lon >= bbox[1] && lat <= bbox[2] && lon <= bbox[3]
    }

    /**
     * Every bundled place, in chunks, for the one-time seed of the searchable
     * gazetteer.
     *
     * The pack was drawn on the map from the day it landed but was never
     * searchable: offline search reads the gazetteer, and the gazetteer only
     * ever filled from live Overpass answers. So a phone that had never been
     * online in Kyiv could draw a thousand pins and find none of them.
     */
    fun chunked(size: Int = 500): Sequence<List<PoiEntity>> =
        pois.asSequence().map { it.toEntity() }.chunked(size)

    /**
     * Every bundled POI inside the box, nearest the box centre first, capped at
     * [limit] so a wide viewport can't hand the map ten thousand pins. A plain
     * linear scan: a few tens of thousands of points is microseconds, and the
     * fetch that would replace it is a network round trip that just failed.
     */
    fun inBox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        limit: Int = 300,
    ): List<PoiEntity> {
        val cLat = (minLat + maxLat) / 2
        val cLon = (minLon + maxLon) / 2
        return pois.asSequence()
            .filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }
            .sortedBy { (it.lat - cLat) * (it.lat - cLat) + (it.lon - cLon) * (it.lon - cLon) }
            .take(limit)
            .map { it.toEntity() }
            .toList()
    }

    /**
     * Category floor for offline `getNearby`: pack POIs matching [selector]
     * within a metric radius, nearest first.
     *
     * Відбір іде по ПОВНІЙ мапі тегів, а не лише по `cat`/`sub`: інакше
     * `shelter_type=bomb_shelter` не збігався б ні з чим за побудовою, скільки
     * б сховищ у паку не лежало.
     */
    fun nearby(
        lat0: Double,
        lon0: Double,
        radiusMeters: Int,
        selector: String,
        limit: Int = 120,
    ): List<PoiEntity> {
        val clauses = TagSelector.clauses(selector)
        if (clauses.isEmpty()) return emptyList()
        val cosLat = cos(lat0 * PI / 180.0)
        val r2 = radiusMeters.toDouble() * radiusMeters.toDouble()
        return pois.asSequence()
            .filter { TagSelector.matches(clauses, it.tags, it.cat, it.sub) }
            .map {
                val dx = (it.lon - lon0) * cosLat * 111_320.0
                val dy = (it.lat - lat0) * 111_320.0
                it to dx * dx + dy * dy
            }
            .filter { it.second <= r2 }
            .sortedBy { it.second }
            .take(limit)
            .map { (it, _) -> it.toEntity() }
            .toList()
    }

    private fun PackedPoi.toEntity() = PoiEntity(
        id = id,
        lat = lat,
        lon = lon,
        name = name,
        category = cat,
        subcategory = sub,
        tags = tags,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse the bundled asset. A malformed pack is an empty pack, never a crash. */
        fun parse(raw: String): OfflinePoiPack {
            val doc = runCatching { json.decodeFromString(PoiPackDoc.serializer(), raw) }
                .getOrNull() ?: return EMPTY
            return OfflinePoiPack(doc.pois, doc.generated, doc.bbox, doc.certifiedShelters)
        }

        /**
         * Кілька окремо спечених паків (загальний OSM-витяг, офіційні аптеки й
         * медзаклади, пункти незламності) як ОДНЕ ціле для [PoiRepository] —
         * той самий контракт, один обʼєкт, а не третій параметр конструктора,
         * що розповз би по кожному тесту, який уже будує репозиторій.
         *
         * [bbox] лишається за НАЙПЕРШИМ непорожнім паком: рамка — властивість
         * одного витягу (наприклад, київського OSM-паку), а не обʼєднання —
         * зведення меж national+3-обласного набору дало б прямокутник, що бреше
         * про покриття точнісінько так, як застерігає коментар вище.
         */
        fun merge(packs: List<OfflinePoiPack>): OfflinePoiPack {
            val real = packs.filter { it !== EMPTY }
            if (real.isEmpty()) return EMPTY
            if (real.size == 1) return real.single()
            val counted = real.mapNotNull { it.certifiedShelters }
            return OfflinePoiPack(
                pois = real.flatMap { it.pois },
                generated = real.maxOf { it.generated },
                bbox = real.firstOrNull { it.bbox.size >= 4 }?.bbox ?: emptyList(),
                // Жоден пакет не рахував — рахунку немає. Один із трьох рахував
                // нуль, а решта мовчить: це все одно нуль, а не «невідомо».
                certifiedShelters = if (counted.isEmpty()) null else counted.sum(),
            )
        }

        val EMPTY = OfflinePoiPack(emptyList())
    }
}
