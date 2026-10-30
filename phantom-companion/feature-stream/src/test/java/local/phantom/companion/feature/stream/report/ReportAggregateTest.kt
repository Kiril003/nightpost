package local.phantom.companion.feature.stream.report

import local.phantom.companion.feature.stream.ui.report.GeoPoint
import local.phantom.companion.feature.stream.ui.report.RankEntry
import local.phantom.companion.feature.stream.ui.report.ReportAggregate
import local.phantom.companion.feature.stream.ui.report.Slice
import local.phantom.companion.feature.stream.ui.report.WaterfallStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Агрегація — це те, яке саме число оператор побачить, і де саме пройде
 * лінія. Всередині `Canvas` це неперевірне, тому винесено сюди й
 * пришпилено тут — разом із випадками, що ламають наївний код: рівний
 * ряд, порожній ряд, нульова база порівняння, одна точка.
 */
class ReportAggregateTest {

    /* ── Базові агрегати ─────────────────────────────────────────── */

    @Test
    fun `середнє порожнього ряду — нуль, а не помилка ділення`() {
        assertEquals(0.0, ReportAggregate.average(emptyList()), 1e-6)
        assertEquals(0.0, ReportAggregate.total(emptyList()), 1e-6)
    }

    @Test
    fun `дельта від нульової бази не вигадує нескінченність`() {
        assertNull(ReportAggregate.deltaPercent(12.0, 0.0))
        assertEquals(20.0, ReportAggregate.deltaPercent(12.0, 10.0)!!, 1e-6)
        // База відʼємна: напрямок має лишитись правильним.
        assertEquals(50.0, ReportAggregate.deltaPercent(-5.0, -10.0)!!, 1e-6)
    }

    @Test
    fun `медіана парного ряду — середнє двох центральних`() {
        assertEquals(3.5, ReportAggregate.median(listOf(1f, 2f, 5f, 9f)), 1e-6)
        assertEquals(5.0, ReportAggregate.median(listOf(9f, 1f, 5f)), 1e-6)
        assertEquals(0.0, ReportAggregate.median(emptyList()), 1e-6)
    }

    @Test
    fun `перцентиль методом найближчого рангу`() {
        val speeds = (1..100).map { it.toFloat() }
        assertEquals(95.0, ReportAggregate.percentile(speeds, 95.0), 1e-6)
        assertEquals(1.0, ReportAggregate.percentile(speeds, 0.0), 1e-6)
        assertEquals(100.0, ReportAggregate.percentile(speeds, 100.0), 1e-6)
    }

    /* ── Геометрія рядів ─────────────────────────────────────────── */

    @Test
    fun `рівний ряд лягає на середину поля, а не дає NaN`() {
        val flat = ReportAggregate.normalize(listOf(62f, 62f, 62f))
        assertEquals(listOf(0.5f, 0.5f, 0.5f), flat)
    }

    @Test
    fun `нормалізація розтягує ряд на весь діапазон`() {
        assertEquals(listOf(0f, 0.5f, 1f), ReportAggregate.normalize(listOf(10f, 20f, 30f)))
    }

    @Test
    fun `кілька рядів нормалізуються спільно, а не кожен у свій масштаб`() {
        val out = ReportAggregate.normalizeTogether(
            listOf(listOf(0f, 10f), listOf(90f, 100f)),
        )
        // Якби кожен ряд масштабувався окремо, обидва дали б [0,1] і
        // діаграма показала б два однакові ряди замість нижнього й верхнього.
        assertEquals(0f, out[0][0], 1e-6f)
        assertEquals(0.1f, out[0][1], 1e-6f)
        assertEquals(0.9f, out[1][0], 1e-6f)
        assertEquals(1f, out[1][1], 1e-6f)
    }

    @Test
    fun `ряд з однієї точки дає крапку в центрі, а не порожнечу`() {
        val pts = ReportAggregate.sparkPoints(listOf(42f), 100f, 40f)
        assertEquals(1, pts.size)
        assertEquals(50f, pts[0].first, 1e-6f)
        assertEquals(20f, pts[0].second, 1e-6f)
    }

    @Test
    fun `спарклайн розкладає точки рівним кроком і перевертає вісь Y`() {
        val pts = ReportAggregate.sparkPoints(listOf(0f, 10f), 100f, 40f)
        assertEquals(0f, pts[0].first, 1e-6f)
        assertEquals(100f, pts[1].first, 1e-6f)
        // Найменше значення — біля низу поля, найбільше — біля верху.
        assertTrue(pts[0].second > pts[1].second)
    }

    /* ── Похідні структури ───────────────────────────────────────── */

    @Test
    fun `гістограма включає максимум в останню корзину`() {
        val bins = ReportAggregate.histogram(listOf(0f, 5f, 10f), 2)
        assertEquals(2, bins.size)
        // 10 — це максимум; наївне ((v-lo)/step).toInt() дало б індекс 2,
        // значення випало б із гістограми і сума стала б 2.
        assertEquals(3, bins.sumOf { it.count })
        assertEquals(2, bins.last().count)
    }

    @Test
    fun `гістограма рівного ряду — одна корзина`() {
        val bins = ReportAggregate.histogram(listOf(7f, 7f, 7f), 8)
        assertEquals(1, bins.size)
        assertEquals(3, bins.first().count)
    }

    @Test
    fun `водоспад накопичує кроки, а підсумок малює від нуля`() {
        val runs = ReportAggregate.waterfall(
            listOf(
                WaterfallStep("трафік", 6f),
                WaterfallStep("світлофори", 3f),
                WaterfallStep("обʼїзд", -2f),
                WaterfallStep("разом", 0f, total = true),
            ),
        )
        assertEquals(0f, runs[0].from, 1e-6f)
        assertEquals(6f, runs[0].to, 1e-6f)
        assertEquals(6f, runs[1].from, 1e-6f)
        assertEquals(9f, runs[1].to, 1e-6f)
        assertEquals(7f, runs[2].to, 1e-6f)
        assertTrue(runs[3].isTotal)
        assertEquals(0f, runs[3].from, 1e-6f)
        assertEquals(7f, runs[3].to, 1e-6f)
    }

    @Test
    fun `частки кільця нормалізуються до одиниці`() {
        val shares = ReportAggregate.shares(
            listOf(Slice("пальне", 60f), Slice("паркінг", 20f), Slice("мийка", 20f)),
        )
        assertEquals(1f, shares.sum(), 1e-5f)
        assertEquals(0.6f, shares[0], 1e-5f)
    }

    @Test
    fun `нульова сума не ділить на нуль`() {
        assertEquals(listOf(0f, 0f), ReportAggregate.shares(listOf(Slice("a", 0f), Slice("b", 0f))))
    }

    @Test
    fun `рейтинг сортується за спаданням, частка рахується від лідера`() {
        val ranked = ReportAggregate.ranked(
            listOf(
                RankEntry("Саксаганського", 12f),
                RankEntry("Хрещатик", 40f),
                RankEntry("Набережне шосе", 20f),
            ),
        )
        assertEquals("Хрещатик", ranked[0].first.label)
        assertEquals(1f, ranked[0].second, 1e-6f)
        assertEquals(0.5f, ranked[1].second, 1e-6f)
        assertEquals(0.3f, ranked[2].second, 1e-6f)
    }

    @Test
    fun `теплова карта нормалізується по всій матриці, не по рядках`() {
        val heat = ReportAggregate.heat(listOf(listOf(1f, 2f), listOf(5f, 10f)))
        // Рядкова нормалізація зробила б обидва рядки однаково «гарячими».
        assertEquals(0.1f, heat[0][0], 1e-6f)
        assertEquals(1f, heat[1][1], 1e-6f)
    }

    @Test
    fun `смуга швидкості має допуск навколо межі`() {
        assertEquals(ReportAggregate.SpeedBand.UNDER, ReportAggregate.speedBand(40f, 50f))
        assertEquals(ReportAggregate.SpeedBand.NEAR, ReportAggregate.speedBand(48f, 50f))
        assertEquals(ReportAggregate.SpeedBand.NEAR, ReportAggregate.speedBand(54f, 50f))
        assertEquals(ReportAggregate.SpeedBand.OVER, ReportAggregate.speedBand(62f, 50f))
        // Обмеження невідоме — не звинувачуємо водія.
        assertEquals(ReportAggregate.SpeedBand.UNDER, ReportAggregate.speedBand(120f, 0f))
    }

    /* ── Географія ───────────────────────────────────────────────── */

    @Test
    fun `проєкція зберігає пропорцію сліду`() {
        // Квадратна за формою ділянка в полі 200×100 має лишитись
        // квадратною: розтягнута на всю ширину, вона змінила б кути
        // повороту, і слід перестав би бути свідченням.
        val square = listOf(
            GeoPoint(50.4500, 30.5000),
            GeoPoint(50.4500, 30.5100),
            GeoPoint(50.4564, 30.5100),
            GeoPoint(50.4564, 30.5000),
        )
        val pts = ReportAggregate.project(square, 200f, 100f)
        val width = pts.maxOf { it.first } - pts.minOf { it.first }
        val height = pts.maxOf { it.second } - pts.minOf { it.second }
        assertTrue("сторони мали лишитись близькими: $width vs $height", abs(width - height) < 8f)
        assertTrue(pts.all { it.first in 0f..200f && it.second in 0f..100f })
    }

    @Test
    fun `проєкція вкладається у поле з відступом`() {
        val pts = ReportAggregate.project(
            listOf(GeoPoint(50.44, 30.51), GeoPoint(50.46, 30.53)),
            200f,
            120f,
            padding = 10f,
        )
        assertTrue(pts.all { it.first >= 9.9f && it.first <= 190.1f })
        assertTrue(pts.all { it.second >= 9.9f && it.second <= 110.1f })
    }

    @Test
    fun `відстань Київ — Ліма відтворює зафіксовану розбіжність 21 липня`() {
        // Подія 2026-07-21: супутникове рішення показало Ліму, мережеве
        // лишилось у Києві. Це число — доказ у звіті, тому воно пришпилене.
        val kyiv = GeoPoint(50.4501, 30.5234)
        val lima = GeoPoint(-12.0464, -77.0428)
        val km = ReportAggregate.distanceKm(kyiv, lima)
        assertEquals(12278.0, km, 5.0)
    }

    @Test
    fun `відстань між сусідніми точками міста — близько кілометра`() {
        val a = GeoPoint(50.4501, 30.5234)
        val b = GeoPoint(50.4600, 30.5300)
        assertEquals(1.196, ReportAggregate.distanceKm(a, b), 0.01)
    }

    @Test
    fun `порожні входи не валять геометрію`() {
        assertTrue(ReportAggregate.project(emptyList(), 100f, 100f).isEmpty())
        assertTrue(ReportAggregate.sparkPoints(emptyList(), 100f, 100f).isEmpty())
        assertTrue(ReportAggregate.histogram(emptyList(), 5).isEmpty())
        assertTrue(ReportAggregate.ranked(emptyList()).isEmpty())
    }
}
