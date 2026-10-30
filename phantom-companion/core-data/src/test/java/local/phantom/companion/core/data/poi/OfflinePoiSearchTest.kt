package local.phantom.companion.core.data.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HIGHWAY — the rules of offline search, with no database in the loop.
 *
 * Two families of bug live here, and both are silent:
 *  - a query that is not sanitised is a crash or a wrong answer (Ukrainian is
 *    full of apostrophes, and FTS reads them as syntax);
 *  - a ranking that trusts text over distance sends a driver 8 km backwards.
 */
class OfflinePoiSearchTest {

    // ── sanitising ──────────────────────────────────────────────────────

    @Test
    fun `an apostrophe in a Ukrainian word is not FTS syntax`() {
        // «Кавʼярня» is an ordinary word and a broken FTS string literal.
        // Будь-яким із апострофів — і ʼ, яким пише продукт, і ASCII з клавіатури.
        for (raw in listOf("Кавʼярня", "Кавʼярня", "Кавʼярня")) {
            val q = OfflinePoiSearch.toMatchQuery(raw)!!
            assertEquals(raw, "кав* ярня*", q)
            assertTrue("no apostrophe may survive", q.none { it in "'ʼ’" })
        }
    }

    @Test
    fun `FTS operators in operator text are neutralised`() {
        // Each of these is an FTS operator, and none of them is one HERE: the
        // man is naming a place, not writing a query language.
        listOf("АЗС OR ОККО", "кава NEAR метро", "-shell", "окко*", "\"unbalanced").forEach { raw ->
            val q = OfflinePoiSearch.toMatchQuery(raw)!!
            // Only lowercase letters/digits and a trailing star per token —
            // nothing FTS could read as an operator (its operators are
            // UPPERCASE-only, and every token here is lowercased).
            assertTrue("syntax survived in: $q", Regex("^([\\p{L}\\p{N}]+\\*)( [\\p{L}\\p{N}]+\\*)*$").matches(q))
        }
    }

    @Test
    fun `tokens are ANDed, and each gets a prefix star`() {
        // «окко азс» must mean both — an OR would bury ОККО under every other
        // petrol station in the county.
        assertEquals("окко* азс*", OfflinePoiSearch.toMatchQuery("ОККО АЗС"))
    }

    @Test
    fun `noise is not a query`() {
        assertNull(OfflinePoiSearch.toMatchQuery(""))
        assertNull(OfflinePoiSearch.toMatchQuery("   "))
        assertNull(OfflinePoiSearch.toMatchQuery("!!! ---"))
        assertNull("one letter is not an intent", OfflinePoiSearch.toMatchQuery("к"))
    }

    // ── the box ─────────────────────────────────────────────────────────

    @Test
    fun `the longitude span widens with latitude`() {
        // At Kyiv (50°N) a degree of longitude is ~64 km against latitude's
        // ~111. A box that used one figure for both would be a third too narrow
        // east-west and would silently lose real hits.
        val (minLat, maxLat, minLon, maxLon) = OfflinePoiSearch
            .boundingBox(50.45, 30.52, 10_000).let {
                listOf(it[0], it[1], it[2], it[3])
            }
        val latSpan = maxLat - minLat
        val lonSpan = maxLon - minLon
        assertTrue("longitude span must exceed latitude span at 50°N", lonSpan > latSpan * 1.4)
    }

    @Test
    fun `the box contains everything inside the radius`() {
        val box = OfflinePoiSearch.boundingBox(50.45, 30.52, 5_000)
        // A point 4.9 km due east is inside the radius, so it must be in the box.
        val east = 30.52 + (4_900 / (111_320.0 * Math.cos(Math.toRadians(50.45))))
        assertTrue(east <= box[3])
    }

    @Test
    fun `the poles do not blow the box up`() {
        val box = OfflinePoiSearch.boundingBox(89.999, 0.0, 50_000)
        assertTrue("longitude span must stay finite", box[3].isFinite() && box[2].isFinite())
    }

    // ── ranking ─────────────────────────────────────────────────────────

    private fun poi(id: Long, name: String, lat: Double, lon: Double, sub: String? = "fuel") =
        PoiGazetteerEntity(id, lat, lon, name, "amenity", sub)

    @Test
    fun `distance beats a better name`() {
        val here = 50.45 to 30.52
        // A perfectly-named station 6 km away, and an adequately-named one
        // 300 m ahead. bm25 would pick the first. A driver would not.
        val far = poi(1, "ОККО", 50.50, 30.52)
        val near = poi(2, "ОККО АЗС №14", 50.4527, 30.52)

        val ranked = OfflinePoiSearch.rank(
            listOf(far, near), here.first, here.second, radiusMeters = 25_000, query = "окко",
        )
        assertEquals("the near one is the answer", 2L, ranked.first().id)
    }

    @Test
    fun `inside one distance band the name decides`() {
        val here = 50.45 to 30.52
        // Both ~within the same 250 m band: now the name is allowed to speak.
        val vague = poi(1, "АЗС Придорожня", 50.4508, 30.52)
        val exact = poi(2, "ОККО", 50.4510, 30.52)

        val ranked = OfflinePoiSearch.rank(
            listOf(vague, exact), here.first, here.second, radiusMeters = 25_000, query = "окко",
        )
        assertEquals(2L, ranked.first().id)
    }

    @Test
    fun `the square box does not leak into the round answer`() {
        val here = 50.45 to 30.52
        // Diagonally outside a 1 km circle but inside the 1 km SQL box — the
        // corner case the box over-selects on purpose, and the circle removes.
        val corner = poi(1, "АЗС", 50.4590, 30.5340)
        val ranked = OfflinePoiSearch.rank(
            listOf(corner), here.first, here.second, radiusMeters = 1_000, query = "азс",
        )
        val d = OfflinePoiSearch.haversineMeters(50.45, 30.52, 50.4590, 30.5340)
        assertTrue("fixture must sit outside the circle (was ${d}m)", d > 1_000)
        assertTrue("a corner of the box is not within the radius", ranked.isEmpty())
    }

    // ── рятувальний прохід ──────────────────────────────────────────────

    @Test
    fun `у рятунку якість збігу вирішує раніше за відстань`() {
        // Порядок тут навмисно ІНШИЙ, ніж у rank: рядки прийшли не з FTS, тож
        // збіги різної якості лежать поруч. Випадкова описка за 100 м не сміє
        // ставати попереду точної назви за 2 км.
        val here = 50.45 to 30.52
        val partialNear = poi(1, "Хрещатик Плаза", 50.4510, 30.52) // ~110 м, збіг за словом
        val exactFar = poi(2, "Хрещатик", 50.4700, 30.52) // ~2,2 км, точна назва

        val ranked = OfflinePoiSearch.rescue(
            listOf(partialNear, exactFar), here.first, here.second, radiusMeters = 25_000, query = "хрещатик",
        )
        assertEquals(listOf(2L, 1L), ranked.map { it.id })
    }

    @Test
    fun `рятунок не віддає того, що не збіглося`() {
        val ranked = OfflinePoiSearch.rescue(
            listOf(poi(1, "Хрещатик", 50.4505, 30.52)),
            50.45, 30.52, radiusMeters = 25_000, query = "стоматологія",
        )
        assertTrue("найближче місце — не відповідь на будь-яке слово", ranked.isEmpty())
    }

    @Test
    fun `distance is reported, so the car head can say how far`() {
        val ranked = OfflinePoiSearch.rank(
            listOf(poi(1, "ОККО", 50.4590, 30.52)),
            50.45, 30.52, radiusMeters = 5_000, query = "окко",
        )
        assertEquals(1, ranked.size)
        // ~1 km north.
        assertTrue(ranked.first().distanceMeters in 900.0..1_100.0)
    }

    // ── одне місце, записане двічі ──────────────────────────────────────

    /**
     * Та сама аптека лежить у газетирі двома рядками: з довідника (id
     * виведений з `ref`) і з живої відповіді Overpass (id — сирий номер OSM).
     * Попереднє звуження за id їх не бачило, і людині показувало «Подорожник»
     * двічі поспіль.
     */
    @Test
    fun `one place written twice comes back once`() {
        val fromPack = PoiGazetteerEntity(
            id = 800_000_000_000_111L, lat = 50.45, lon = 30.52, name = "Подорожник",
            category = "amenity", subcategory = "pharmacy", seenAt = 1_000L,
            tags = mapOf(OfflinePlacePack.REF_TAG to "n111"),
        )
        val fromOverpass = fromPack.copy(
            id = 111L, seenAt = 2_000L,
            tags = mapOf(
                OfflinePlacePack.REF_TAG to "n111",
                "amenity" to "pharmacy",
                "opening_hours" to "Mo-Su 08:00-22:00",
            ),
        )
        val collapsed = OfflinePoiSearch.collapseByRef(listOf(fromPack, fromOverpass))
        assertEquals(1, collapsed.size)
        // Виграє БАГАТШИЙ на теги: інакше картка показала б аптеку без годин
        // у місті, де застосунок їх уже знав.
        assertEquals("Mo-Su 08:00-22:00", collapsed.single().tags["opening_hours"])
    }

    @Test
    fun `two different places are never mistaken for one`() {
        val a = PoiGazetteerEntity(
            id = 800_000_000_000_111L, lat = 50.45, lon = 30.52, name = "Подорожник",
            category = "amenity", subcategory = "pharmacy",
            tags = mapOf(OfflinePlacePack.REF_TAG to "n111"),
        )
        val b = a.copy(id = 810_000_000_000_111L, name = "Аптека 9-1-1",
            tags = mapOf(OfflinePlacePack.REF_TAG to "w111"))
        assertEquals(2, OfflinePoiSearch.collapseByRef(listOf(a, b)).size)
    }

    /**
     * Рядок без `ref` — старий асет, київський `poi.json`, запис із часів до
     * цієї хвилі — звужується за id, тобто рівно як раніше. Зшивання не сміє
     * нічого зліпити там, де немає чим зліплювати: два безіменні ключі `null`
     * стали б одним місцем.
     */
    @Test
    fun `rows without a ref behave exactly as before`() {
        val a = poi(1, "ОККО", 50.45, 30.52)
        val b = poi(2, "WOG", 50.46, 30.52)
        val again = poi(1, "ОККО", 50.45, 30.52)
        assertEquals(2, OfflinePoiSearch.collapseByRef(listOf(a, b, again)).size)
    }

    // ── корені ──────────────────────────────────────────────────────────

    @Test
    fun `корінь латинського запиту пишеться кирилицею`() {
        val roots = OfflinePoiSearch.roots("Khreshchatyk")
        assertTrue("свій корінь мусить лишитись: $roots", roots.contains("khr*"))
        assertTrue("без кириличного кореня FTS не дасть жодного кандидата: $roots", roots.contains("хре*"))
    }

    @Test
    fun `корінь кириличного запиту пишеться й латиницею`() {
        assertTrue(OfflinePoiSearch.roots("Сільпо").contains("sil*"))
    }

    @Test
    fun `корінь не згортає мʼякого знака у своєму ж написанні`() {
        // Згортання зливає і→и і зʼїдає ь, тож «Сільпо» стало б «сил*» і не
        // знайшло б себе в індексі, де лежить сире «сільпо».
        assertTrue(OfflinePoiSearch.roots("Сільпо").contains("сіл*"))
    }

    @Test
    fun `двослівний запит дає корінь кожного слова`() {
        assertTrue(OfflinePoiSearch.roots("Нова пошта").contains("нов* пош*"))
    }

    @Test
    fun `надто коротке слово кореня не дає`() {
        assertEquals(emptyList<String>(), OfflinePoiSearch.roots("ок"))
        assertEquals(emptyList<String>(), OfflinePoiSearch.roots("1"))
    }

    @Test
    fun `за межами коробки вирішує якість збігу, а не відстань`() {
        // Точна назва за 470 км мусить стояти попереду однокореневої вулиці за
        // рогом: цей прохід узагалі існує лише тому, що поруч не знайшлось.
        val city = poi(1, "Бориспіль", 50.3527, 30.9550)
        val street = poi(2, "Бориспільська вулиця", 49.8397, 24.0297)
        val ranked = OfflinePoiSearch.rankByQuality(
            rows = listOf(street, city),
            lat = 49.8397,
            lon = 24.0297,
            query = "Бориспіль",
        )
        assertEquals(listOf(1L, 2L), ranked.map { it.id })
    }

    @Test
    fun `якісний порядок теж не вигадує місць`() {
        val ranked = OfflinePoiSearch.rankByQuality(
            rows = listOf(poi(1, "Хрещатик", 50.45, 30.52)),
            lat = 50.45,
            lon = 30.52,
            query = "стоматологія",
        )
        assertEquals(emptyList<PoiHit>(), ranked)
    }

    // ── питання про вид місця ───────────────────────────────────────────

    private fun ask(q: String, vararg rows: PoiGazetteerEntity): List<Long> {
        val kind = PoiVocabulary.kindOf(q)
        return OfflinePoiSearch.rank(
            rows.toList(), 50.45, 30.52, radiusMeters = 25_000, query = q, kinds = kind.kinds, rest = kind.rest,
        ).map { it.id }
    }

    @Test
    fun `банк — це банк, а не банкомат з таким словом у назві на тому ж розі`() {
        val atm = poi(1, "Райффайзен Банк", 50.4501, 30.52, sub = "atm")
        val bank = poi(2, "Сенс Банк", 50.4540, 30.52, sub = "bank")
        assertEquals(2L, ask("банк", atm, bank).first())
    }

    @Test
    fun `вокзал — не Вокзальна вулиця і не аптека на Вокзальній`() {
        val street = poi(1, "Вокзальна вулиця", 50.4505, 30.52, sub = "residential")
        val pharmacy = poi(2, "Аптечний пункт, вул. Вокзальна, 10", 50.4506, 30.52, sub = "pharmacy")
        assertEquals(emptyList<Long>(), ask("вокзал", street, pharmacy))
    }

    @Test
    fun `Нова пошта раніше за Укрпошту, що ближче`() {
        val ukr = poi(1, "Відділення поштового звʼязку №6", 50.4501, 30.52, sub = "post_office")
        val nova = poi(2, "Нова Пошта №12", 50.4560, 30.52, sub = "post_office")
        assertEquals(listOf(2L), ask("Нова пошта", ukr, nova))
    }

    @Test
    fun `railway station — вокзал, а не заправка, що теж petrol station`() {
        val fuel = poi(1, "WOG", 50.4505, 30.52, sub = "fuel")
        val station = poi(2, "Київ-Пасажирський", 50.4400, 30.52, sub = "station")
        assertEquals(listOf(2L), ask("railway station", fuel, station))
    }
}
