package local.phantom.companion.core.geo

import local.phantom.companion.core.data.spatial.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спільна геометрія карти: гаверсинус, локальна проєкція, різниця курсів.
 *
 * Перевірка «від істини»: беремо відстані, які відомі з незалежного джерела
 * (Київ–Львів ≈ 468 км по великому колу), і вимагаємо їх від коду. Це ловить
 * плутанину радіан/градусів, підміну радіуса Землі й помилку в порядку
 * аргументів — усе те, що на короткому відрізку ділянки контролю непомітне, а на
 * маршруті між містами дає десятки кілометрів похибки.
 */
class GeoMathTest {

    // Київ (Майдан) і Львів (площа Ринок) — координати з точністю до сотень метрів.
    private val kyivLat = 50.4501
    private val kyivLon = 30.5234
    private val lvivLat = 49.8397
    private val lvivLon = 24.0297

    @Test
    fun `велике коло відтворює відому відстань Київ–Львів`() {
        // Еталон (сферична модель, R=6371 км): ≈ 467.5 км.
        val d = GeoMath.greatCircleM(kyivLat, kyivLon, lvivLat, lvivLon)
        assertEquals("Київ–Львів по великому колу ≈ 467.5 км", 467_530.0, d, 1_000.0)
    }

    @Test
    fun `велике коло симетричне щодо порядку точок`() {
        val ab = GeoMath.greatCircleM(kyivLat, kyivLon, lvivLat, lvivLon)
        val ba = GeoMath.greatCircleM(lvivLat, lvivLon, kyivLat, kyivLon)
        assertEquals("відстань не залежить від напрямку", ab, ba, 1e-6)
    }

    @Test
    fun `нульова відстань справді нуль, а не мікрошум`() {
        // Приймач, що стоїть на місці, не має «дрейфувати» через похибку формули —
        // інакше нерухомий пристрій рахував би собі пробіг.
        assertEquals("велике коло в тій самій точці", 0.0, GeoMath.greatCircleM(kyivLat, kyivLon, kyivLat, kyivLon), 1e-9)
        assertEquals("локальна проєкція в тій самій точці", 0.0, GeoMath.distanceM(kyivLat, kyivLon, kyivLat, kyivLon), 1e-9)
    }

    @Test
    fun `антиподи дають половину великого кола Землі`() {
        // Найдовший можливий відрізок: діаметрально протилежні точки. π·R.
        val halfCircumference = Math.PI * GeoMath.EARTH_RADIUS_M
        assertEquals(
            "екватор, 0° і 180°",
            halfCircumference, GeoMath.greatCircleM(0.0, 0.0, 0.0, 180.0), 1.0,
        )
        assertEquals(
            "справжні антиподи (50,30) ↔ (-50,-150)",
            halfCircumference, GeoMath.greatCircleM(50.0, 30.0, -50.0, -150.0), 1.0,
        )
    }

    @Test
    fun `на масштабі станції локальна проєкція збігається з великим колом`() {
        // Уся причина, чому модуль за замовчуванням не гаверсинус: на сотнях метрів
        // локальна рівнокутна проєкція дає ту саму відповідь дешевше. Якби ні,
        // маршрут у метро й пошук камери рахувались би з похибкою.
        val gc = GeoMath.greatCircleM(kyivLat, kyivLon, 50.4530, 30.5290) // ≈ 500 м по діагоналі
        val local = GeoMath.distanceM(kyivLat, kyivLon, 50.4530, 30.5290)
        assertTrue("на ~0.5 км різниця має бути в сантиметрах, а не в метрах", Math.abs(gc - local) < 0.5)
    }

    @Test
    fun `різниця курсів не плутається на переході через північ`() {
        // Наївне abs(a-b) дало б 340° замість 20° — і саме там ламається перевірка
        // «камера попереду чи позаду».
        assertEquals("350° і 10° різняться на 20°, а не на 340°", 20.0, GeoMath.headingDeltaDeg(350.0, 10.0), 1e-9)
        assertEquals("протилежні курси — рівно 180°", 180.0, GeoMath.headingDeltaDeg(0.0, 180.0), 1e-9)
        assertEquals("однакові курси — нуль", 0.0, GeoMath.headingDeltaDeg(90.0, 90.0), 1e-9)
    }

    @Test
    fun `азимут на схід дорівнює приблизно девʼяносто градусів`() {
        val east = GeoMath.bearingDeg(kyivLat, kyivLon, kyivLat, kyivLon + 0.01)
        assertEquals("рух на схід — азимут ≈ 90°", 90.0, east, 0.5)
        val north = GeoMath.bearingDeg(kyivLat, kyivLon, kyivLat + 0.01, kyivLon)
        assertEquals("рух на північ — азимут ≈ 0°", 0.0, north, 0.5)
    }

    @Test
    fun `нормалізація кута зводить будь-яке значення у нуль-триста-шістдесят`() {
        assertEquals(350.0, GeoMath.normalizeDeg(-10.0), 1e-9)
        assertEquals(10.0, GeoMath.normalizeDeg(370.0), 1e-9)
        assertEquals(0.0, GeoMath.normalizeDeg(360.0), 1e-9)
    }

    @Test
    fun `переведення швидкості оборотне`() {
        assertEquals("90 км/год = 25 м/с", 25.0, GeoMath.kmhToMps(90.0), 1e-9)
        assertEquals("оборотність км/год → м/с → км/год", 130.0, GeoMath.mpsToKmh(GeoMath.kmhToMps(130.0)), 1e-9)
    }

    // ── Довжина ламаної ───────────────────────────────────────────────────

    @Test
    fun `градус широти по меридіану — відомі 111,19 км`() {
        // Еталон рахується без коду: R * 1° у радіанах = 6371000 * π/180.
        val oneDegree = GeoMath.pathLengthM(
            listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)),
        )
        assertEquals("градус широти ≈ 111 194.9 м", 111_194.9, oneDegree, 1.0)
    }

    @Test
    fun `два однакові відрізки дають рівно подвійну довжину`() {
        val one = GeoMath.pathLengthM(listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)))
        val two = GeoMath.pathLengthM(
            listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0), GeoPoint(2.0, 0.0)),
        )

        // Сума відрізків, а не відстань між кінцями: якби ланки не додавались,
        // тут була б довжина від нуля до двійки, тобто те саме число.
        assertEquals("дві ланки по градусу", 2 * one, two, 1.0)
    }

    @Test
    fun `ламана з однієї вершини або порожня має нульову довжину`() {
        assertEquals(0.0, GeoMath.pathLengthM(emptyList()), 0.0)
        assertEquals(0.0, GeoMath.pathLengthM(listOf(GeoPoint(kyivLat, kyivLon))), 0.0)
    }

    @Test
    fun `гак через проміжну точку не коротший за пряму`() {
        val direct = GeoMath.greatCircleM(kyivLat, kyivLon, lvivLat, lvivLon)
        val viaNorth = GeoMath.pathLengthM(
            listOf(
                GeoPoint(kyivLat, kyivLon),
                GeoPoint(51.5, 27.0),
                GeoPoint(lvivLat, lvivLon),
            ),
        )

        // Нерівність трикутника ловить переставлені аргументи й ланку,
        // порахувану від не тієї вершини.
        assertTrue("гак ($viaNorth) коротший за пряму ($direct)", viaNorth > direct)
    }
}
