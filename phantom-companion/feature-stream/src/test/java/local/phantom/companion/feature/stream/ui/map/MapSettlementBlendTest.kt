package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.poi.PoiHit
import local.phantom.companion.core.geo.place.NamedPlace
import local.phantom.companion.core.geo.place.PlaceGazetteer
import local.phantom.companion.core.geo.place.PlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Місто — теж місце.
 *
 * Червоніло до правки: довідник на 315 українських місць був доступний рівно з
 * одного екрана (пікера офлайн-пакетів), тож набране в пошуку «Львів» не
 * знаходило Львова — у видачі були лише ті POI, які телефон колись бачив.
 */
class MapSettlementBlendTest {

    // Київ, Майдан.
    private val lat = 50.4501
    private val lon = 30.5234

    private fun poi(id: Long, name: String) = PoiHit(
        id = id,
        lat = lat + 0.001,
        lon = lon,
        name = name,
        category = "amenity",
        subcategory = "cafe",
        distanceMeters = 111.0,
    )

    private fun place(id: String, ua: String, en: String, kind: PlaceKind = PlaceKind.City) =
        NamedPlace(
            id = id,
            nameUa = ua,
            nameEn = en,
            parentUa = null,
            kind = kind,
            northLat = 49.90,
            eastLon = 24.10,
            southLat = 49.78,
            westLon = 23.95,
            landAreaKm2 = null,
            osmRef = null,
        )

    @Test
    fun `точно названий населений пункт іде першим`() {
        val out = blendSettlements(
            poiHits = listOf(poi(1, "Львівська кавʼярня")),
            settlements = listOf(place("m-lviv", "Львів", "Lviv")),
            query = "Львів",
            lat = lat,
            lon = lon,
        )
        assertEquals(listOf("Львів", "Львівська кавʼярня"), out.map { it.name })
        assertEquals("місто мусить нести свій рівень", "city", out.first().subcategory)
        assertTrue("і відстань до себе, а не нуль", out.first().distanceMeters > 100_000)
    }

    @Test
    fun `недодрукована назва не витісняє того, що поруч`() {
        // Людина ще друкує «льв». Поховати під областями кавʼярню за 111 м
        // було б гірше за все — місто замикає видачу, а не веде її.
        val out = blendSettlements(
            poiHits = listOf(poi(1, "Львівська кавʼярня")),
            settlements = listOf(place("m-lviv", "Львів", "Lviv")),
            query = "льв",
            lat = lat,
            lon = lon,
        )
        assertEquals(listOf("Львівська кавʼярня", "Львів"), out.map { it.name })
    }

    @Test
    fun `збіг усередині слова містом не є`() {
        val out = blendSettlements(
            poiHits = emptyList(),
            settlements = listOf(place("m-kyiv", "Київ", "Kyiv")),
            query = "ів",
            lat = lat,
            lon = lon,
        )
        assertTrue("«ів» у «Київ» — не назва міста", out.isEmpty())
    }

    @Test
    fun `апостроф і регістр не ховають місто`() {
        // Те саме згортання, що й у пікері: PlaceGazetteer.fold.
        val out = blendSettlements(
            poiHits = emptyList(),
            settlements = listOf(place("m-mizhhiria", "Міжгірʼя", "Mizhhiria")),
            query = "міжгірʼя",
            lat = lat,
            lon = lon,
        )
        assertEquals(listOf("Міжгірʼя"), out.map { it.name })
    }

    @Test
    fun `порожній запит не вивалює список країн`() {
        // PlaceGazetteer.searchEverywhere("") повертає країни — і жодна з них
        // не є відповіддю на порожній рядок.
        val out = blendSettlements(
            poiHits = listOf(poi(1, "Сільпо")),
            settlements = PlaceGazetteer.countries(),
            query = "",
            lat = lat,
            lon = lon,
        )
        assertEquals(listOf("Сільпо"), out.map { it.name })
    }

    // ── СИРЕНА-4: ближче йде першим ──────────────────────────────────────

    private fun placeAt(id: String, ua: String, north: Double, east: Double, south: Double, west: Double) =
        NamedPlace(
            id = id, nameUa = ua, nameEn = ua, parentUa = null, kind = PlaceKind.City,
            northLat = north, eastLon = east, southLat = south, westLon = west,
            landAreaKm2 = null, osmRef = null,
        )

    @Test
    fun `серед часткових збігів ближчий іде першим, а не той, кого рушій тексту вивів першим`() {
        // Скарга з екрана в дії: PlaceGazetteer.searchEverywhere не знає ні
        // широти, ні довготи — три однаково-текстово підхожі населені пункти
        // приходять сюди в порядку рушія тексту (тут навмисно «найдальший
        // спершу», щоб довести, що порядок на виході задає саме сортування за
        // відстанню в [blendSettlements], а не порядок вхідного списку).
        val here = 49.8209 to 18.2625
        val far = placeAt("m-far", "Раївка-Далека", 48.20, 17.20, 48.10, 17.05)
        val mid = placeAt("m-mid", "Раївка-Середня", 49.25, 16.65, 49.15, 16.55)
        val near = placeAt("m-near", "Раївка-Ближня", 50.10, 18.25, 50.05, 18.15)

        val out = blendSettlements(
            poiHits = emptyList(),
            settlements = listOf(far, mid, near),
            query = "раївка",
            lat = here.first,
            lon = here.second,
            limit = 3,
        )

        assertEquals(
            listOf("Раївка-Ближня", "Раївка-Середня", "Раївка-Далека"),
            out.map { it.name },
        )
        assertTrue("ближня мусить бути найближчою", out[0].distanceMeters < out[1].distanceMeters)
        assertTrue("середня мусить бути ближчою за далеку", out[1].distanceMeters < out[2].distanceMeters)
    }

    @Test
    fun `точний збіг лишається попереду часткового, навіть коли частковий ближче`() {
        val far = place("m-far", "Львів", "Lviv") // exact
        val near = placeAt("m-near", "Львівщина", 50.10, 24.20, 50.00, 24.10) // partial, closer
        val out = blendSettlements(
            poiHits = emptyList(),
            settlements = listOf(near, far),
            query = "львів",
            lat = lat,
            lon = lon,
        )
        assertEquals("точний збіг не поступається місцем ближчому частковому", "Львів", out.first().name)
    }

    @Test
    fun `місто, вже знайдене серед POI, не показується двічі`() {
        val out = blendSettlements(
            poiHits = listOf(poi(1, "Львів")),
            settlements = listOf(place("m-lviv", "Львів", "Lviv")),
            query = "Львів",
            lat = lat,
            lon = lon,
        )
        assertEquals(1, out.size)
    }
}
