package local.phantom.companion.core.data.transit.gtfs

import local.phantom.companion.core.data.transit.FeedBounds
import local.phantom.companion.core.data.transit.GtfsScheduleFeed
import local.phantom.companion.core.data.transit.raptor.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * СПРАВЖНІЙ РОЗКЛАД КИЄВА, НАСКРІЗЬ.
 *
 * Фікстура — незмінений архів КП «Київпастранс», знятий 13.09.2026 з
 * `http://193.23.225.211:8002/export-gtfs-static` (оголошено на data.gov.ua,
 * CC-BY), sha256 3ba72ded…1dd5ce5, 1 204 372 Б. Не макет, не урізаний
 * зразок: ті самі 4,2 МБ `stop_times.txt`, які поїдуть у телефон.
 *
 * Тест проганяє повний бойовий шлях — [GtfsStaticParser.parse] →
 * [local.phantom.companion.core.data.transit.raptor.TimetableBuilder.build]
 * → [GtfsSchedule.departuresNear] — і звіряє КОНКРЕТНІ хвилини, звірені
 * незалежно проти сирого CSV.
 *
 * ЧОМУ ЦЕ ВЗАГАЛІ ДОКАЗ, А НЕ ЩЕ ОДИН ЗЕЛЕНИЙ ТЕСТ. Тролейбус 18 на
 * м-ні Незалежності о 12:08, далі 12:33, далі 12:56 — це 25 і 23 хвилини
 * поспіль. Інтервальна модель, яку цей шлях заступає, не здатна видати
 * два різні проміжки підряд ЗА ПОБУДОВОЮ: вона додає стале `headway`.
 * Нерівність цих двох чисел і є підпис справжнього розкладу.
 *
 * ЧЕСНА МЕЖА ЦЬОГО ТЕСТУ: він доводить розбір і видачу часів, а НЕ те, що
 * завантаження працює сьогодні. Живу дорогу (200, 1,2 МБ, application/zip)
 * переміряно руками 13.09.2026; тут мережі нема навмисно — тест у воротах
 * не має залежати від муніципального сервера, який лежить у вівторок.
 */
class KyivGtfsScheduleTest {

    /** День, яким архів себе назвав. Неділя — звідси `weekday = 7`. */
    private val serviceDate = "20260913"
    private val weekday = 7

    private val kyiv = GtfsScheduleFeed(
        city = "Київ",
        anchorLat = 50.4501,
        anchorLon = 30.5234,
        radiusKm = 35.0,
        staticUrl = "http://193.23.225.211:8002/export-gtfs-static",
        attribution = "КП «Київпастранс» / КМДА, data.gov.ua, CC-BY",
    )

    private fun fixture() = javaClass.classLoader!!
        .getResourceAsStream("transit/kyiv_gtfs_20260913.zip")!!

    private fun schedule(): GtfsSchedule {
        val feed = GtfsStaticParser.parse(fixture(), serviceDate, weekday)
        val built = GtfsSchedule.build(
            feed = feed,
            city = kyiv.city,
            serviceDate = serviceDate,
            attribution = kyiv.attribution,
            expected = kyiv.expectedBounds,
            fetchedAtEpochMs = 0L,
        )
        assertTrue(
            "архів мав зібратись у розклад, а не: $built",
            built is GtfsSchedule.Companion.Build.Ready,
        )
        return (built as GtfsSchedule.Companion.Build.Ready).schedule
    }

    private fun clock(sec: Int): String =
        "%02d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)

    @Test
    fun `архів київпастрансу дає справжній розклад на день служби`() {
        val s = schedule()

        assertEquals("feed_version архіву", "20260913", s.feedVersion)
        assertEquals(serviceDate, s.serviceDate)

        // 1 479 зупинок, усі в межах міста — виміряно окремо по сирому CSV.
        assertEquals(1_479, s.timetable.stopCount)

        // 10 601 рейс на неділю. Порівняння «більше за», бо будівник
        // законно викидає рейси з єдиним викликом — але порядок має бути
        // саме такий, а не сотня.
        assertTrue("рейсів на день служби: ${s.tripCount}", s.tripCount > 9_000)

        // ГОЛОВНЕ: жоден маршрут звідси не змодельований.
        assertFalse(
            "розклад із GTFS не має права нести жодного модельованого маршруту",
            s.timetable.routeTimesModelled.any { it },
        )
    }

    @Test
    fun `на майдані незалежності о 12 годині їдуть справжні тролейбуси`() {
        val s = schedule()
        val noon = 12 * 3600

        val departures = s.departuresNear(
            lat = 50.4501,
            lon = 30.5234,
            radiusM = 400,
            nowSec = noon,
        )
        assertTrue("біля майдану мали знайтись відправлення", departures.isNotEmpty())

        // Кожен рядок — реальна хвилина з stop_times.txt, звірена проти CSV.
        val firstOf = { ref: String ->
            departures.filter { it.routeLabel == ref }.minByOrNull { it.departureSec }
        }

        val t18 = firstOf("18")
        assertEquals("тролейбус 18, перше відправлення", "12:08:00", clock(t18!!.departureSec))
        assertEquals(TransitMode.TROLLEYBUS, t18.mode)
        assertEquals("м-н Незалежності", t18.stopName)
        assertEquals(8, t18.minutesFrom(noon))

        assertEquals("тролейбус 16", "12:07:00", clock(firstOf("16")!!.departureSec))
        assertEquals("тролейбус 6", "12:06:00", clock(firstOf("6")!!.departureSec))

        // Кінцеву не віддаємо: на м-ні Незалежності обидва маршрути мають
        // напрямок, що ТУТ закінчується, і «відправлення» там не існує.
        assertTrue(
            "жодне відправлення не сміє стояти на кінцевій",
            departures.none { it.terminusName == it.stopName },
        )
    }

    @Test
    fun `нерівні проміжки доводять розклад, а не інтервальну модель`() {
        val s = schedule()
        val noon = 12 * 3600

        val line18 = s.departuresNear(50.4501, 30.5234, 400, noon, perRoute = 3)
            .filter { it.routeLabel == "18" }
            .map { it.departureSec }
            .distinct()
            .sorted()

        assertTrue("тролейбус 18 мав дати щонайменше три рейси", line18.size >= 3)
        assertEquals(listOf("12:08:00", "12:33:00", "12:56:00"), line18.take(3).map(::clock))

        val gapA = line18[1] - line18[0]
        val gapB = line18[2] - line18[1]
        assertEquals(25 * 60, gapA)
        assertEquals(23 * 60, gapB)
        // Ось воно: модель зі сталим headway не вміє дати 25 і 23 підряд.
        assertTrue("проміжки мусять бути різними", gapA != gapB)
    }

    /**
     * ВИМІР, А НЕ ПОБАЖАННЯ. Я писав цей тест навпаки — «архів мусить
     * нести часи за 24:00:00», — і він почервонів. Порахував по сирому
     * CSV: 103 184 рядки `stop_times.txt`, найпізніший 23:59:00, за
     * 86 400 — РІВНО НУЛЬ.
     *
     * Тобто київський фід обриває добу опівночі й нічного руху в ньому
     * нема взагалі. Це властивість ДЖЕРЕЛА, і вона лишається тут
     * записаною: якщо КП колись почне писати «24:40:00», цей тест впаде і
     * змусить перечитати, а не тихо пропустить зміну.
     */
    @Test
    fun `київський архів обриває добу на 23-59 і нічного руху не має`() {
        val s = schedule()
        val latest = s.timetable.departures.max()
        assertEquals("найпізніше відправлення архіву", "23:59:00", clock(latest))
        assertEquals(
            "рядків за 24:00:00 у цьому джерелі нема",
            0,
            s.timetable.departures.count { it >= 86_400 },
        )
    }

    /**
     * Попри те, що Київ таких часів не пише, розбирач мусить їх тримати:
     * львівський архів і метро цим живуть, а загортання в 24 години
     * зрізало б останній рейс саме тоді, коли він найпотрібніший.
     */
    @Test
    fun `розбирач тримає час за межею доби`() {
        assertEquals(25 * 3600 + 10 * 60, GtfsStaticParser.parseGtfsTime("25:10:00"))
        assertEquals(24 * 3600 + 40 * 60, GtfsStaticParser.parseGtfsTime(" 24:40:00 "))
    }

    @Test
    fun `фід з чужими координатами відхиляється цілком`() {
        val feed = GtfsStaticParser.parse(fixture(), serviceDate, weekday)
        // Та сама перевірка, що спіймала б виміряний раніше фід, у якому
        // 53 % координат лежали поза містом: рамка Львова проти київських
        // зупинок має відхилити ВЕСЬ архів, а не показати пів міста.
        val built = GtfsSchedule.build(
            feed = feed,
            city = "Львів",
            serviceDate = serviceDate,
            attribution = "—",
            expected = FeedBounds(south = 49.75, west = 23.90, north = 49.93, east = 24.16),
            fetchedAtEpochMs = 0L,
        )
        assertTrue(
            "київський архів не сміє пройти як львівський: $built",
            built is GtfsSchedule.Companion.Build.Rejected,
        )
    }

    @Test
    fun `вчорашній розклад визнає себе вчорашнім`() {
        val s = schedule()
        assertFalse(s.isStaleFor("20260913"))
        // Київський архів перегенеровується щодоби — назавтра цей знімок
        // протух, і сказати про це мусить він сам.
        assertTrue(s.isStaleFor("20260914"))
    }
}
