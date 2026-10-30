package local.phantom.companion.core.voice.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Живі українські репліки як тестові вектори. Тут вирішується, чи PHANTOM
 * звучить розумним: відповісти на «подзвони мамі» за 700 мс — добре;
 * відповісти на «подзвони, якщо» — катастрофа, бо людина ще думала.
 */
class TurnTakerTest {

    /** Проганяє тишу від 0 до [toMs] по 50 мс і повертає перший не-Wait вердикт. */
    private fun tickTo(
        taker: TurnTaker,
        partial: String,
        toMs: Long,
        afterQuestion: Boolean = false,
    ): Pair<TurnDecision, Long> {
        var t = 0L
        while (t <= toMs) {
            val d = taker.onTick(t, partial, afterQuestion)
            if (d != TurnDecision.Wait) return d to t
            t += 50L
        }
        return TurnDecision.Wait to t
    }

    private fun firstCommitMs(partial: String, afterQuestion: Boolean = false): Long {
        val taker = TurnTaker()
        var t = 0L
        while (t <= 5_000L) {
            if (taker.onTick(t, partial, afterQuestion) == TurnDecision.Commit) return t
            t += 50L
        }
        return -1L
    }

    /* ── Класифікація хвоста ───────────────────────────────────────────────── */

    @Test
    fun `завершена фраза читається як завершена`() {
        val lex = TailLexicon.UKRAINIAN
        assertEquals(TailClass.COMPLETE, lex.classify("подзвони мамі"))
        assertEquals(TailClass.COMPLETE, lex.classify("так"))
        assertEquals(TailClass.COMPLETE, lex.classify("постав таймер на десять хвилин"))
        assertEquals(TailClass.COMPLETE, lex.classify("скільки зараз годин?"))
    }

    @Test
    fun `обірвана на сполучнику фраза не завершена`() {
        val lex = TailLexicon.UKRAINIAN
        assertEquals(TailClass.INCOMPLETE, lex.classify("подзвони мамі, але"))
        assertEquals(TailClass.INCOMPLETE, lex.classify("я думаю що"))
        assertEquals(TailClass.INCOMPLETE, lex.classify("зроби це щоб"))
        assertEquals(TailClass.INCOMPLETE, lex.classify("нагадай мені про"))
        assertEquals(TailClass.INCOMPLETE, lex.classify("поїдемо до"))
        assertEquals(TailClass.INCOMPLETE, lex.classify("ну"))
    }

    @Test
    fun `кома в кінці — та сама обірвана думка`() {
        assertEquals(TailClass.INCOMPLETE, TailLexicon.UKRAINIAN.classify("подзвони мамі,"))
        assertEquals(TailClass.INCOMPLETE, TailLexicon.UKRAINIAN.classify("по-перше —"))
    }

    @Test
    fun `саме лише вагання — це не репліка`() {
        assertEquals(TailClass.FILLER, TailLexicon.UKRAINIAN.classify("ееее"))
        assertEquals(TailClass.FILLER, TailLexicon.UKRAINIAN.classify("хм мм"))
        assertEquals(TailClass.EMPTY, TailLexicon.UKRAINIAN.classify("   "))
    }

    /* ── Політика черги ────────────────────────────────────────────────────── */

    @Test
    fun `завершена репліка закривається за 700 мс`() {
        assertEquals(700L, firstCommitMs("подзвони мамі"))
    }

    @Test
    fun `відповідь на запитання PHANTOM отримує швидку смугу`() {
        assertEquals(450L, firstCommitMs("так", afterQuestion = true))
    }

    @Test
    fun `пауза на роздум не отримує швидкої смуги навіть після запитання`() {
        // «подзвони… якщо» — людина ще добирає слово. 1200 мс, не 450.
        assertEquals(1200L, firstCommitMs("подзвони мамі якщо", afterQuestion = true))
    }

    @Test
    fun `здогад вилітає раніше за закриття і рівно один раз`() {
        val taker = TurnTaker()
        val (first, at) = tickTo(taker, "постав таймер", toMs = 600L)
        assertEquals(TurnDecision.Provisional, first)
        assertEquals(350L, at)
        assertTrue(taker.speculated)
        // Далі до самого закриття — жодного другого здогаду.
        var t = 400L
        var second: TurnDecision = TurnDecision.Wait
        while (t < 700L) {
            val d = taker.onTick(t, "постав таймер")
            if (d != TurnDecision.Wait) second = d
            t += 50L
        }
        assertEquals(TurnDecision.Wait, second)
        assertEquals(TurnDecision.Commit, taker.onTick(700L, "постав таймер"))
    }

    @Test
    fun `незакінчений хвіст ніколи не породжує здогаду`() {
        val taker = TurnTaker()
        var t = 0L
        while (t < 1200L) {
            assertTrue(
                "здогад на «$t мс» після обірваної фрази",
                taker.onTick(t, "подзвони мамі але") != TurnDecision.Provisional,
            )
            t += 50L
        }
        assertFalse(taker.speculated)
    }

    @Test
    fun `нестабільний партіал не пускає здогад`() {
        val taker = TurnTaker()
        // Розпізнавач ще перебирає слова — щотика інший текст.
        assertEquals(TurnDecision.Wait, taker.onTick(350L, "постав"))
        assertEquals(TurnDecision.Wait, taker.onTick(400L, "постав таймер"))
        assertEquals(TurnDecision.Wait, taker.onTick(450L, "постав таймер на"))
    }

    @Test
    fun `довга тиша після уламка — видиме терпіння, а не відповідь`() {
        val taker = TurnTaker()
        // Уламок не закривається взагалі, поки хвіст — саме вагання.
        var sawPatience = false
        var t = 0L
        while (t <= 4_000L) {
            val d = taker.onTick(t, "ееее")
            assertTrue("уламок «ееее» не мав закриватись", d != TurnDecision.Commit)
            if (d == TurnDecision.Patience) sawPatience = true
            t += 50L
        }
        assertTrue("після 2.5 с мовчання треба показати, що досі слухаємо", sawPatience)
    }

    @Test
    fun `продовження після здогаду скасовує його`() {
        // «подзвони… [пауза] …мамі ввечері» — одна репліка, не дві.
        val taker = TurnTaker()
        assertEquals(TurnDecision.Provisional, tickTo(taker, "подзвони", toMs = 400L).first)
        taker.onSpeechResumed()
        assertFalse(taker.speculated)

        var t = 0L
        var closed = -1L
        while (t <= 1_000L) {
            if (taker.onTick(t, "подзвони мамі ввечері") == TurnDecision.Commit) {
                closed = t
                break
            }
            t += 50L
        }
        assertEquals(700L, closed)
    }

    @Test
    fun `після закриття мовчить, поки не скинули`() {
        val taker = TurnTaker()
        var t = 0L
        var committed = false
        while (t <= 800L) {
            if (taker.onTick(t, "добре") == TurnDecision.Commit) committed = true
            t += 50L
        }
        assertTrue(committed)
        assertEquals(TurnDecision.Wait, taker.onTick(3_000L, "добре"))
        taker.reset()
        assertEquals(TurnDecision.Wait, taker.onTick(0L, ""))
    }
}
