package local.phantom.companion.core.data.evac

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import local.phantom.companion.core.data.poi.PoiDao
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A ranked shelter/cover candidate, closest first. */
data class ShelterToken(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double,
    val assetType: ShelterAssetType = ShelterAssetType.BOMB_SHELTER,
)

/** M-C05/C06 — Law 5 (Offline-Survival): honest outcome of a shelter scan. */
sealed interface ShelterScanResult {
    /**
     * [offline] — served from the local cache, not fresh Overpass.
     * [radiusMeters] — the radius step at which this hit landed.
     * [tokens].first().assetType != BOMB_SHELTER when tag-broadening kicked in.
     */
    data class Found(val tokens: List<ShelterToken>, val offline: Boolean, val radiusMeters: Int) : ShelterScanResult

    /** Radial expansion AND tag broadening both exhausted — genuinely nothing nearby. */
    data object NoAssetsFound : ShelterScanResult

    /**
     * The registry could not be consulted at all: Overpass refused and the local
     * cache held nothing. Distinct from [NoAssetsFound] because under an air alarm
     * «джерело недоступне» and «укриттів нема» send the operator in opposite directions.
     */
    data class SourceDown(val reason: SourceUnavailable) : ShelterScanResult
}

/** Stepped radial expansion — 3km / 6km / 12km — tried in order until something lands. */
private val RADIUS_STEPS_METERS = listOf(3000, 6000, 12000)

/**
 * Offline-first shelter registry with graceful degradation. A certified-shelter
 * miss is never a dead end: at each radius step every candidate structure type
 * from [ShelterTruth.TIERS] is tried (bomb shelter → metro → school →
 * administrative building → place of worship) before the radius expands to the
 * next step. Every hit — fresh or cached — is ranked by distance; the caller
 * decides what "degraded" means for the UI (offline cache, improvised asset
 * type, or an expanded radius all count).
 *
 * Що саме тут вважається сховищем, вирішує [ShelterTruth] і більше ніхто:
 * `amenity=shelter` — це навіс, і власного списку тегів у цього класу немає.
 */
class ShelterRepository(
    private val poiDao: PoiDao,
    private val overpassClient: OverpassClient,
) {
    suspend fun findNearest(
        lat: Double,
        lon: Double,
        initialRadiusMeters: Int = RADIUS_STEPS_METERS.first(),
    ): ShelterScanResult = withContext(Dispatchers.IO) {
        val steps = RADIUS_STEPS_METERS.filter { it >= initialRadiusMeters }
            .ifEmpty { listOf(initialRadiusMeters) }

        var outage: SourceUnavailable? = null

        for (radius in steps) {
            val fresh = freshByType(lat, lon, radius)
            outage = outage ?: fresh.outage
            // Порядок «свіже, потім кеш» ітерується ПО ТИПАХ, а не по радіусах:
            // кешоване справжнє укриття має лишитись важливішим за свіжу школу,
            // як було до обʼєднання запитів.
            for (tier in ShelterTruth.TIERS) {
                fresh.byType[tier.assetType]?.takeIf { it.isNotEmpty() }?.let { entities ->
                    return@withContext ShelterScanResult.Found(
                        rank(entities, lat, lon, tier.assetType), offline = false, radiusMeters = radius,
                    )
                }
                val cached = cachedNear(lat, lon, radius, tier)
                if (cached.isNotEmpty()) {
                    return@withContext ShelterScanResult.Found(
                        rank(cached, lat, lon, tier.assetType), offline = true, radiusMeters = radius,
                    )
                }
            }
        }
        // Порожньо І джерело мовчало — це «не знаємо», а не «нема».
        outage?.let { return@withContext ShelterScanResult.SourceDown(it) }
        ShelterScanResult.NoAssetsFound
    }

    private data class FreshScan(
        val byType: Map<ShelterAssetType, List<PoiEntity>>,
        val outage: SourceUnavailable?,
    )

    /**
     * Один обʼєднаний запит на радіус замість чотирьох послідовних.
     *
     * Сканування запускає повітряна тривога — тобто саме тоді, коли Overpass
     * під піком і кожен зайвий похід у мережу коштує секунди перед тим, як
     * оператор дізнається, куди бігти.
     */
    private suspend fun freshByType(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
    ): FreshScan {
        val probe = try {
            overpassClient.fetchAroundAnyResult(
                lat, lon, radiusMeters, ShelterTruth.TIERS.flatMap { it.selectors }, limit = UNION_LIMIT,
            )
        } catch (ce: CancellationException) {
            // Скасування сканування мусить дійти до того, хто скасував:
            // проковтнуте, воно виглядало б як «укриттів поруч немає».
            throw ce
        } catch (t: Throwable) {
            GeoResult.Unavailable(SourceUnavailable.Transport(t.javaClass.simpleName))
        }
        val elements = when (probe) {
            is GeoResult.Ok -> probe.value
            is GeoResult.Unavailable -> {
                Log.w(TAG, "скан укриттів r=$radiusMeters: джерело недоступне (${probe.reason.detail}) — йдемо в кеш")
                return FreshScan(emptyMap(), probe.reason)
            }
        }

        val byType = ShelterTruth.TIERS.associate { tier ->
            tier.assetType to elements.filter { tier.matches(it.tags.orEmpty()) }
                .mapNotNull { it.toEntity(tier) }
        }
        val all = byType.values.flatten()
        // Кешуються всі типи, а не лише повернутий: обʼєднаний запит уже
        // заплачений, і викидати решту означало б платити за неї знову.
        if (all.isNotEmpty()) runCatching { poiDao.insertPois(all) }
        return FreshScan(byType, outage = null)
    }

    private suspend fun cachedNear(lat: Double, lon: Double, radiusMeters: Int, tier: ShelterTier): List<PoiEntity> {
        val latDelta = radiusMeters / 111_320.0
        val lonDelta = radiusMeters / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val box = poiDao.getPoisInBoundingBox(
            minLat = lat - latDelta,
            maxLat = lat + latDelta,
            minLon = lon - lonDelta,
            maxLon = lon + lonDelta,
        ).first()
        return box.filter { tier.matches(it.tags, it.category, it.subcategory) }
    }

    private fun rank(entities: List<PoiEntity>, lat: Double, lon: Double, assetType: ShelterAssetType): List<ShelterToken> =
        entities.map { ShelterToken(it.id, it.name, it.lat, it.lon, haversineMeters(lat, lon, it.lat, it.lon), assetType) }
            .sortedBy { it.distanceMeters }

    private fun OverpassElement.toEntity(tier: ShelterTier): PoiEntity? {
        val elLat = effectiveLat ?: return null
        val elLon = effectiveLon ?: return null
        return PoiEntity(
            id = id,
            lat = elLat,
            lon = elLon,
            name = tags?.get("name") ?: tier.defaultNameUk,
            category = tier.pinCategory,
            subcategory = tier.pinSubcategory,
            tags = tags ?: emptyMap(),
        )
    }

    private companion object {
        const val TAG = "ShelterRepository"

        /** Стеля `out center` на обʼєднаний запит — по 80 на кожен щабель. */
        const val UNION_LIMIT = 560
    }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
