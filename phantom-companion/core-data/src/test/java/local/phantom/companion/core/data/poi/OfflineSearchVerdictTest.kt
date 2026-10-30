package local.phantom.companion.core.data.poi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пошук на пристрої від запиту людини до вироку — з підробленою базою, але
 * без підробленої відповіді.
 *
 * ЧЕСНІСТЬ ПІДРОБКИ. [FtsLikeDao] навмисно вміє рівно те, що вміє FTS4 з
 * `unicode61` на живому пристрої: складає регістр, ріже рядок на не-літерах і
 * зіставляє ПОЧАТОК токена. Він НЕ вміє української згортки, не вміє описок і
 * не вміє другої абетки — бо їх не вміє й SQLite. Саме тому цей набір ловить
 * справжню ваду: усе, що підробка не знаходить, продукт мусить знайти сам.
 *
 * ЧОГО ЦЕЙ НАБІР НЕ ДОВОДИТЬ: як обрізає справжня SQLite. Порядок `ORDER BY`
 * у DAO перевіряє `GazetteerSearchTest` на пристрої — тут його вдає підробка,
 * і вдавання не є свідченням.
 */
class OfflineSearchVerdictTest {

    // Київ, Майдан.
    private val lat = 50.4501
    private val lon = 30.5234

    private fun place(
        id: Long,
        name: String,
        lat: Double,
        lon: Double,
        sub: String? = "cafe",
        keywords: String = "",
    ) = PoiGazetteerEntity(
        id = id,
        lat = lat,
        lon = lon,
        name = name,
        category = "amenity",
        subcategory = sub,
        keywords = keywords,
    )

    /** Довідник, який відповідає так само вузько, як FTS4 на телефоні. */
    private class FtsLikeDao(seed: List<PoiGazetteerEntity> = emptyList()) : PoiGazetteerDao {
        val rows = seed.toMutableList()

        override suspend fun insertPlaces(places: List<PoiGazetteerEntity>) {
            rows.removeAll { row -> places.any { it.id == row.id } }
            rows += places
        }

        override suspend fun insertTerms(terms: List<PoiFtsEntity>) = Unit

        override suspend fun searchInBox(
            query: String,
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> {
            // `toMatchQuery` дає «токен* токен*» — саме це FTS і отримує.
            val terms = query.split(' ').map { it.removeSuffix("*") }.filter { it.isNotEmpty() }
            return rows.asSequence()
                .filter { inBox(it, minLat, maxLat, minLon, maxLon) }
                .filter { row ->
                    val tokens = tokenize(row.name + " " + row.keywords)
                    terms.all { term -> tokens.any { it.startsWith(term) } }
                }
                .sortedBy { squareDistance(it, atLat, atLon, lonScaleSq) }
                .take(limit)
                .toList()
        }

        override suspend fun searchAnywhere(
            query: String,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> {
            val terms = query.split(' ').map { it.removeSuffix("*") }.filter { it.isNotEmpty() }
            return rows.asSequence()
                .filter { row ->
                    val tokens = tokenize(row.name + " " + row.keywords)
                    terms.all { term -> tokens.any { it.startsWith(term) } }
                }
                .sortedBy { squareDistance(it, atLat, atLon, lonScaleSq) }
                .take(limit)
                .toList()
        }

        override suspend fun byCategoryInBox(
            subcategories: List<String>,
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> = rows.asSequence()
            .filter { it.subcategory in subcategories && inBox(it, minLat, maxLat, minLon, maxLon) }
            .sortedBy { squareDistance(it, atLat, atLon, lonScaleSq) }
            .take(limit)
            .toList()

        override suspend fun nearestPlacesInBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> =
            nearestInBox(minLat, maxLat, minLon, maxLon, atLat, atLon, lonScaleSq, Int.MAX_VALUE)
                .filter { it.category != "street" && it.category != "place" }
                .take(limit)

        override suspend fun nearestInBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> = rows.asSequence()
            .filter { inBox(it, minLat, maxLat, minLon, maxLon) }
            .sortedBy { squareDistance(it, atLat, atLon, lonScaleSq) }
            .take(limit)
            .toList()

        override suspend fun inBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            limit: Int,
        ) = rows.filter { inBox(it, minLat, maxLat, minLon, maxLon) }.take(limit)

        override suspend fun byIds(ids: List<Long>) = rows.filter { it.id in ids }

        override suspend fun size() = rows.size

        private fun inBox(
            row: PoiGazetteerEntity,
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
        ) = row.lat in minLat..maxLat && row.lon in minLon..maxLon

        private fun squareDistance(
            row: PoiGazetteerEntity,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
        ): Double {
            val dLat = row.lat - atLat
            val dLon = row.lon - atLon
            return dLat * dLat + dLon * dLon * lonScaleSq
        }

        /** unicode61: складає регістр і ріже на всьому, що не літера й не цифра. */
        private fun tokenize(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }

    private class NoPoiDao : PoiDao {
        override fun getPoisInBoundingBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
        ): Flow<List<PoiEntity>> = flowOf(emptyList())

        override suspend fun insertPois(pois: List<PoiEntity>) = Unit
        override suspend fun clearExpired(expiryTime: Long) = Unit
    }

    private fun repo(dao: PoiGazetteerDao?) = PoiRepository(poiDao = NoPoiDao(), gazetteer = dao)

    // ── якість пошуку ───────────────────────────────────────────────────

    @Test
    fun `«крещатик» знаходить «Хрещатик»`() = runTest {
        // Червоніло до правки: FTS шукає «крещатик*» і не знаходить нічого, бо
        // перша літера інша — а це та сама вулиця, просто іншою орфографією.
        val dao = FtsLikeDao(listOf(place(1, "Хрещатик", lat + 0.002, lon)))
        val hits = repo(dao).searchOffline("крещатик", lat, lon)
        assertEquals(listOf(1L), hits.map { it.id })
    }

    @Test
    fun `«Хрещатик», «хрещатик» і «хрещатик 1» знаходять одне й те саме`() = runTest {
        val dao = FtsLikeDao(listOf(place(1, "Хрещатик", lat + 0.002, lon, keywords = "хрещатик 1")))
        val r = repo(dao)
        val answers = listOf("Хрещатик", "хрещатик", "хрещатик 1", "крещатик")
            .map { r.searchOffline(it, lat, lon).map { h -> h.id } }
        assertTrue("усі чотири написання мусять дати одне місце: $answers", answers.all { it == listOf(1L) })
    }

    @Test
    fun `апостроф не робить із місця збіг «лише по суті»`() = runTest {
        // Червоніло до правки: у даних друкарський «’», у запиті клавіатурний
        // «'», і сирий contains оголошував рядок збігом НЕ за назвою.
        val dao = FtsLikeDao(listOf(place(1, "Кавʼярня Львівська", lat + 0.001, lon)))
        val hit = repo(dao).searchOffline("кавʼярня", lat, lon).single()
        assertTrue("це збіг саме за назвою", hit.matchedName)
    }

    @Test
    fun `«політех» знаходить інститут за початком другого слова`() = runTest {
        // Сторож, а не правка: FTS уміє початок будь-якого токена, і мусить
        // далі вміти. Падіння тут означає, що зламали `toMatchQuery`.
        val dao = FtsLikeDao(listOf(place(7, "Київський політехнічний інститут", lat + 0.003, lon)))
        assertEquals(listOf(7L), repo(dao).searchOffline("політех", lat, lon).map { it.id })
    }

    @Test
    fun `назва латиницею знаходиться кирилицею`() = runTest {
        // Червоніло до правки: FTS не переходить між абетками взагалі.
        val dao = FtsLikeDao(listOf(place(3, "Lviv Croissants", lat + 0.001, lon)))
        assertEquals(listOf(3L), repo(dao).searchOffline("львів", lat, lon).map { it.id })
    }

    @Test
    fun `ближче — вище`() = runTest {
        val dao = FtsLikeDao(
            listOf(
                place(1, "АЗС ОККО", lat + 0.05, lon, sub = "fuel"),
                place(2, "АЗС ОККО", lat + 0.002, lon, sub = "fuel"),
                place(3, "АЗС ОККО", lat + 0.02, lon, sub = "fuel"),
            ),
        )
        val hits = repo(dao).searchOffline("окко", lat, lon)
        assertEquals(listOf(2L, 3L, 1L), hits.map { it.id })
        assertTrue(hits[0].distanceMeters < hits[1].distanceMeters)
        assertTrue(hits[1].distanceMeters < hits[2].distanceMeters)
    }

    @Test
    fun `рятунок не вигадує місць`() = runTest {
        // Найближче місце поруч — але воно не має нічого спільного із запитом.
        // Рятувальний прохід не сміє віддати його «бо більше нічого немає».
        val dao = FtsLikeDao(listOf(place(1, "Хрещатик", lat + 0.001, lon)))
        val verdict = repo(dao).searchOfflineVerdict("стоматологія", lat, lon)
        assertTrue(verdict is PoiRepository.OfflineSearch.NothingHere)
        assertEquals(emptyList<PoiHit>(), verdict.hits)
    }

    // ── вирок ───────────────────────────────────────────────────────────

    @Test
    fun `порожній довідник — це не «нічого не знайдено»`() = runTest {
        // До правки обидва стани приходили однаковим порожнім списком, і
        // людині казали «нічого не знайдено» там, де правда була «шукати нема
        // де»: пакет місць не встановлено.
        assertEquals(
            PoiRepository.OfflineSearch.NowhereToLook,
            repo(FtsLikeDao()).searchOfflineVerdict("хрещатик", lat, lon),
        )
        assertEquals(
            "довідника немає взагалі — це той самий вирок",
            PoiRepository.OfflineSearch.NowhereToLook,
            repo(null).searchOfflineVerdict("хрещатик", lat, lon),
        )
    }

    @Test
    fun `є де шукати, але цього тут немає`() = runTest {
        val dao = FtsLikeDao((1..12).map { place(it.toLong(), "Крамниця $it", lat + 0.001, lon) })
        val verdict = repo(dao).searchOfflineVerdict("стоматологія", lat, lon)
        assertEquals(PoiRepository.OfflineSearch.NothingHere(corpusSize = 12), verdict)
    }

    @Test
    fun `знайдене приходить як знайдене`() = runTest {
        val dao = FtsLikeDao(listOf(place(1, "Хрещатик", lat + 0.001, lon)))
        val verdict = repo(dao).searchOfflineVerdict("хрещатик", lat, lon)
        assertTrue(verdict is PoiRepository.OfflineSearch.Hits)
        assertEquals(listOf(1L), verdict.hits.map { it.id })
    }

    // ── за межами коробки ───────────────────────────────────────────────

    /** Довідник країнний: поруч з людиною завжди щось є, інакше це не «далеко», а «нема де». */
    private val neighbour = place(99, "Туалет", lat + 0.003, lon, sub = "toilets", keywords = "туалет")

    /** Львів — 470 км від Майдану, тобто далеко поза будь-якою коробкою. */
    private val farLat = 49.8397
    private val farLon = 24.0297

    @Test
    fun `місто за 470 км знаходиться, а не мовчить`() = runTest {
        // Червоніло до правки: довідник країнний, а пошук був районний —
        // 25 км навколо людини. «Бориспіль» зі Львова давав нуль рядків при
        // повному довіднику в кишені.
        val dao = FtsLikeDao(listOf(place(1, "Бориспіль", farLat, farLon), neighbour))
        val hits = repo(dao).searchOffline("бориспіль", lat, lon)
        assertEquals(listOf(1L), hits.map { it.id })
    }

    @Test
    fun `поруч усе одно попереду далекого`() = runTest {
        // Розширення не сміє перебити коробку: якщо поруч є збіг, країнний
        // прохід не запускається взагалі.
        val dao = FtsLikeDao(
            listOf(
                place(1, "Хрещатик", farLat, farLon),
                place(2, "Хрещатик", lat + 0.001, lon),
            ),
        )
        assertEquals(listOf(2L), repo(dao).searchOffline("хрещатик", lat, lon).map { it.id })
    }

    @Test
    fun `латиниця знаходить кирилицю за 470 км`() = runTest {
        // FTS уміє лише префікс у тій самій абетці, тож «Khreshchatyk» не
        // давав зіставлячу ЖОДНОГО рядка — а той роками вміє дві абетки.
        val dao = FtsLikeDao(listOf(place(1, "Хрещатик", farLat, farLon), neighbour))
        assertEquals(listOf(1L), repo(dao).searchOffline("Khreshchatyk", lat, lon).map { it.id })
    }

    @Test
    fun `кирилиця знаходить латинську назву`() = runTest {
        val dao = FtsLikeDao(listOf(place(1, "Silpo", farLat, farLon, sub = "supermarket"), neighbour))
        assertEquals(listOf(1L), repo(dao).searchOffline("Сільпо", lat, lon).map { it.id })
    }

    @Test
    fun `описка за 470 км теж знаходиться`() = runTest {
        val dao = FtsLikeDao(listOf(place(1, "Бориспіль", farLat, farLon), neighbour))
        assertEquals(listOf(1L), repo(dao).searchOffline("борисполь", lat, lon).map { it.id })
    }

    @Test
    fun `місто веде видачу, а однокоренева вулиця за рогом іде за ним`() = runTest {
        // «Моршин» зі Львова давав «Моршинську вулицю» за 300 м, а самого
        // міста за 80 км не показував узагалі: коробка знайшла ЩОСЬ, і далі
        // ніхто не питав. Близьке лишається у видачі — просто не першим.
        val dao = FtsLikeDao(
            listOf(
                place(1, "Моршинська вулиця", lat + 0.002, lon, sub = "street"),
                place(2, "Моршин", farLat, farLon, sub = "town"),
            ),
        )
        assertEquals(listOf(2L, 1L), repo(dao).searchOffline("Моршин", lat, lon).map { it.id })
    }

    @Test
    fun `точний збіг поруч не тягне за собою зайвого запиту`() = runTest {
        // Коли поруч уже є місце з такою самою назвою, країни ніхто не питає:
        // вимір ціни цього проходу тримається саме на цій умові.
        val dao = FtsLikeDao(
            listOf(
                place(1, "Моршин", lat + 0.002, lon, sub = "town"),
                place(2, "Моршин", farLat, farLon, sub = "town"),
            ),
        )
        assertEquals(listOf(1L), repo(dao).searchOffline("Моршин", lat, lon).map { it.id })
    }

    @Test
    fun `питання про вид місця не розширюється на країну`() = runTest {
        // «Аптека» питає «що поруч». Аптека за 470 км — не відповідь на це
        // питання, а гірший різновид порожнечі: людина піде туди, куди їй
        // показали. Розширюється лише НАЗВА.
        val dao = FtsLikeDao(
            listOf(place(1, "Подорожник", farLat, farLon, sub = "pharmacy", keywords = "аптека ліки"), neighbour),
        )
        val verdict = repo(dao).searchOfflineVerdict("аптека", lat, lon)
        assertEquals(PoiRepository.OfflineSearch.NothingHere(corpusSize = 2), verdict)
    }

    @Test
    fun `країнний прохід теж не вигадує місць`() = runTest {
        // Найважливіше з усього: розширивши межу, ми не змінили правила про
        // докази. Немає збігу — немає рядка, хай навіть на всю країну.
        val dao = FtsLikeDao(listOf(place(1, "Хрещатик", farLat, farLon), neighbour))
        val verdict = repo(dao).searchOfflineVerdict("стоматологія", lat, lon)
        assertEquals(PoiRepository.OfflineSearch.NothingHere(corpusSize = 2), verdict)
    }

    @Test
    fun `де довідник нічого не знає, чужа країна не видається за відповідь`() = runTest {
        // Прага з українським довідником: «apteka» давало аптеку за 1091 км.
        val dao = FtsLikeDao(listOf(place(1, "Бориспіль", farLat, farLon)))
        assertEquals(
            PoiRepository.OfflineSearch.NowhereToLook,
            repo(dao).searchOfflineVerdict("бориспіль", lat, lon),
        )
    }

    @Test
    fun `вид місця з опискою чи латиницею теж не розширюється`() = runTest {
        val dao = FtsLikeDao(
            listOf(place(1, "Подорожник", farLat, farLon, sub = "pharmacy", keywords = "аптека ліки"), neighbour),
        )
        for (q in listOf("аптіка", "apteka", "pharmacy")) {
            assertEquals(q, emptyList<Long>(), repo(dao).searchOffline(q, lat, lon).map { it.id })
        }
    }

    @Test
    fun `вулиця з номером будинку дає вулицю поруч, а не номер за 470 км`() = runTest {
        val dao = FtsLikeDao(
            listOf(
                place(1, "Городоцька вулиця", lat + 0.004, lon, sub = "primary"),
                place(2, "Аптечний пункт №12 вул.Городоцька,2", farLat, farLon, sub = "pharmacy"),
            ) + (1..600).map { place(1000L + it, "Кіоск", lat + it * 0.000005, lon, sub = "kiosk") },
        )
        assertEquals(1L, repo(dao).searchOffline("Городоцька 12", lat, lon).first().id)
    }

    @Test
    fun `мережа кирилицею знаходить латинську назву`() = runTest {
        val dao = FtsLikeDao(listOf(place(1, "McDonald's", lat + 0.002, lon, sub = "fast_food")))
        assertEquals(listOf(1L), repo(dao).searchOffline("Макдональдс", lat, lon).map { it.id })
    }
}
