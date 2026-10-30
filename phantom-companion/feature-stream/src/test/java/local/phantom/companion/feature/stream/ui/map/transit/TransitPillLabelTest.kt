package local.phantom.companion.feature.stream.ui.map.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Вирок гонтлету, 4 персони: «транзитні чіпи ~36 — маршрут чи хвилини?»
 *
 * Обидва прочитання були законні, і саме тому це помилка, а не смак: голе
 * число поруч із рухомою одиницею — це або номер, або оцінка часу, а «~»
 * перед ним схиляло до другого. Тепер число завжди носить знак номера, а «~»
 * не торкається його ніколи: тильда за доктриною говорить про ПОЗИЦІЮ і живе
 * окремим кутовим чипом.
 *
 * Префікс приходить із ресурсів (укр. «№», англ. «#»), тож таблиця нижче
 * ганяє обидва — і жодна гілка не має права додати «~».
 */
class TransitPillLabelTest {

    private val prefixes = listOf("№", "#")

    @Test
    fun `a live route wears the number sign and nothing else`() {
        prefixes.forEach { p ->
            assertEquals("${p}36", transitPillRoute("36", p))
            assertEquals("${p}7К", transitPillRoute("7К", p))
        }
    }

    /**
     * Головне зобовʼязання: модельна одиниця показує ТОЙ САМИЙ номер. Чесність
     * про позицію несе кутовий чип, а не спотворений номер маршруту.
     */
    @Test
    fun `a simulated vehicle shows the same number, never a tilde-prefixed one`() {
        prefixes.forEach { p ->
            val label = transitPillRoute("36", p)
            assertEquals("${p}36", label)
            assertFalse("тильда вернулась у номер — це знову «≈36 хвилин»", label.contains("~"))
        }
    }

    /** Номера немає — немає й самотнього знака номера. */
    @Test
    fun `no ref means no orphan sign`() {
        prefixes.forEach { p ->
            assertEquals("", transitPillRoute(null, p))
            assertEquals("", transitPillRoute("", p))
            assertEquals("", transitPillRoute("   ", p))
        }
    }

    /** Частина GTFS-джерел віддає ref уже зі знаком. Подвоєння — не номер. */
    @Test
    fun `a feed that already wrote the sign does not get a second one`() {
        prefixes.forEach { p ->
            assertEquals("${p}36", transitPillRoute("$p 36".replace(" ", ""), p))
        }
    }

    @Test
    fun `surrounding whitespace is not part of a route number`() {
        assertEquals("№36", transitPillRoute("  36 ", "№"))
    }
}
