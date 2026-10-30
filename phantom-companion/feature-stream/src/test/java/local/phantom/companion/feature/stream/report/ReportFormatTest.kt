package local.phantom.companion.feature.stream.report

import local.phantom.companion.feature.stream.ui.report.ReportFormat
import local.phantom.companion.feature.stream.ui.report.ReportTone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Форматування — контракт документа, а не деталь рендера.
 *
 * Звіт можна переслати, зберегти й порівняти з учорашнім; якщо той самий
 * агрегат виглядає по-різному залежно від локалі пристрою, порівняння
 * стає неможливим. Тому кожне правило пришпилене тут: кома, вузький
 * нерозривний пробіл, справжній мінус, українська множина.
 */
class ReportFormatTest {

    private val nb = ReportFormat.THIN_NBSP
    private val minus = ReportFormat.MINUS

    @Test
    fun `ціле число без дробової частини`() {
        assertEquals("38", ReportFormat.number(38.0, 0))
        assertEquals("0", ReportFormat.number(0.0, 0))
    }

    @Test
    fun `десятковий роздільник — кома, не крапка`() {
        assertEquals("38,4", ReportFormat.number(38.42, 1))
        assertEquals("7,05", ReportFormat.number(7.049, 2))
    }

    @Test
    fun `розряди від пʼяти знаків розділяються вузьким нерозривним пробілом`() {
        assertEquals("12${nb}270", ReportFormat.number(12270.0, 0))
        assertEquals("1240", ReportFormat.number(1240.0, 0))
        assertEquals("1${nb}234${nb}567", ReportFormat.number(1234567.0, 0))
    }

    @Test
    fun `відʼємне число отримує справжній мінус`() {
        assertEquals("${minus}4,2", ReportFormat.number(-4.2, 1))
    }

    @Test
    fun `округлення до нуля не залишає знака мінус`() {
        // −0,04 з одним знаком — це нуль, і «−0,0» виглядало б як помилка.
        assertEquals("0,0", ReportFormat.number(-0.04, 1))
    }

    @Test
    fun `нечислові величини не вигадують значення`() {
        assertEquals("—", ReportFormat.number(Double.NaN, 1))
        assertEquals("—", ReportFormat.number(Double.POSITIVE_INFINITY, 0))
    }

    @Test
    fun `одиниця приклеюється нерозривним пробілом`() {
        assertEquals("38,4${nb}км", ReportFormat.withUnit(38.4, "км", 1))
        assertEquals("38", ReportFormat.withUnit(38.0, "", 0))
    }

    @Test
    fun `тривалість згортається до найбільшої змістовної одиниці`() {
        assertEquals("4${nb}год 12${nb}хв", ReportFormat.duration(4 * 3600 + 12 * 60))
        assertEquals("38${nb}хв", ReportFormat.duration(38 * 60 + 41))
        assertEquals("52${nb}с", ReportFormat.duration(52))
        assertEquals("—", ReportFormat.duration(-1))
    }

    @Test
    fun `годинник витримує перехід за добу в обидва боки`() {
        assertEquals("08:26", ReportFormat.clock(8 * 60 + 26))
        assertEquals("00:10", ReportFormat.clock(1450))
        assertEquals("23:50", ReportFormat.clock(-10))
    }

    @Test
    fun `відсоток зі знаком, нуль лишається без знака`() {
        assertEquals("+12${nb}%", ReportFormat.signedPercent(12.0))
        assertEquals("${minus}8${nb}%", ReportFormat.signedPercent(-8.0))
        assertEquals("0${nb}%", ReportFormat.signedPercent(0.0))
    }

    @Test
    fun `величина зі знаком і одиницею`() {
        assertEquals("+2,1${nb}км", ReportFormat.signed(2.1, "км", 1))
        assertEquals("${minus}0,4${nb}л", ReportFormat.signed(-0.4, "л", 1))
    }

    @Test
    fun `тон дельти залежить від напрямку, а не від знака`() {
        // +8 % витрати — погано; +8 % економії — добре. Знак однаковий.
        assertEquals(ReportTone.WARN, ReportFormat.deltaTone(8.0, growthIsGood = false))
        assertEquals(ReportTone.GOOD, ReportFormat.deltaTone(8.0, growthIsGood = true))
        assertEquals(ReportTone.GOOD, ReportFormat.deltaTone(-8.0, growthIsGood = false))
        assertEquals(ReportTone.NEUTRAL, ReportFormat.deltaTone(0.0, growthIsGood = true))
    }

    @Test
    fun `українська множина, включно з винятками 11-14`() {
        assertEquals("1${nb}поїздка", ReportFormat.plural(1, "поїздка", "поїздки", "поїздок"))
        assertEquals("3${nb}поїздки", ReportFormat.plural(3, "поїздка", "поїздки", "поїздок"))
        assertEquals("5${nb}поїздок", ReportFormat.plural(5, "поїздка", "поїздки", "поїздок"))
        assertEquals("11${nb}поїздок", ReportFormat.plural(11, "поїздка", "поїздки", "поїздок"))
        assertEquals("12${nb}поїздок", ReportFormat.plural(12, "поїздка", "поїздки", "поїздок"))
        assertEquals("21${nb}поїздка", ReportFormat.plural(21, "поїздка", "поїздки", "поїздок"))
        assertEquals("22${nb}поїздки", ReportFormat.plural(22, "поїздка", "поїздки", "поїздок"))
        assertEquals("0${nb}поїздок", ReportFormat.plural(0, "поїздка", "поїздки", "поїздок"))
    }

    @Test
    fun `гроші показують копійки лише до тисячі`() {
        assertEquals("248,50${nb}₴", ReportFormat.money(248.5))
        assertEquals("12${nb}480${nb}₴", ReportFormat.money(12480.0))
    }

    @Test
    fun `компактні числа для щільних осей`() {
        assertEquals("1,2${nb}тис", ReportFormat.compact(1240.0))
        assertEquals("12${nb}тис", ReportFormat.compact(12270.0))
        assertEquals("1,3${nb}млн", ReportFormat.compact(1_250_000.0))
        assertEquals("62", ReportFormat.compact(62.0))
        assertEquals("4,2", ReportFormat.compact(4.2))
    }

    @Test
    fun `частка обмежується діапазоном`() {
        assertEquals("34${nb}%", ReportFormat.share(0.337))
        assertEquals("100${nb}%", ReportFormat.share(1.4))
        assertEquals("0${nb}%", ReportFormat.share(-0.2))
    }

    @Test
    fun `мілісекунди згортаються за порогами`() {
        assertEquals("420${nb}мс", ReportFormat.millis(420))
        assertEquals("4,2${nb}с", ReportFormat.millis(4200))
        assertEquals("2${nb}хв", ReportFormat.millis(125_000))
    }
}
