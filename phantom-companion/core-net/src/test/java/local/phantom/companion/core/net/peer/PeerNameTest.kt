package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Межа імені — одна, і вона ніколи не мовчить.
 *
 * Привід — кадр зі скла A52 04.09 02:58: імʼя на 45 знаків, надіслане чужим
 * пристроєм, лягло в ростер обрізаним до 40. Посеред слова, без «…», без жодного
 * слова людині. Тести тут ловлять обидві половини тієї поразки: обріз, про який
 * ніхто не сказав, і обріз, який розрізав знак навпіл.
 */
class PeerNameTest {

    // Складається з кодових точок, а не пишеться літералом: у файлі керівний
    // символ виглядає як ніщо, і тест про нього читався б як тест ні про що.
    private val family = cp(0x1F468) + ZWJ + cp(0x1F469) + ZWJ + cp(0x1F467)
    private val yi = cp(0x0438) + cp(0x0306)
    private val flag = cp(0x1F1FA) + cp(0x1F1E6)

    @Test
    fun `рівно на межі імʼя лишається цілим`() {
        val exact = "я".repeat(PeerName.MAX)
        val out = PeerName.accept(exact)
        assertEquals(exact, out.name)
        assertFalse(out.shortened)
    }

    @Test
    fun `на межі плюс один — скорочено, і про це сказано`() {
        val over = "я".repeat(PeerName.MAX + 1)
        val out = PeerName.accept(over)
        assertTrue(out.shortened)
        assertTrue(out.name.endsWith(PeerName.ELLIPSIS))
        // Разом з «…» не довше за межу: інакше «не довше за 64» — не правда.
        assertEquals(PeerName.MAX, PeerName.length(out.name))
        assertEquals("я".repeat(PeerName.MAX - 1) + PeerName.ELLIPSIS, out.name)
    }

    @Test
    fun `сімʼя з емодзі не рветься ні на межі, ні за нею`() {
        assertEquals(1, PeerName.length(family))
        val exact = family.repeat(PeerName.MAX)
        assertFalse(PeerName.accept(exact).shortened)
        assertEquals(exact, PeerName.accept(exact).name)

        val over = PeerName.accept(family.repeat(PeerName.MAX + 1))
        assertTrue(over.shortened)
        assertEquals(family.repeat(PeerName.MAX - 1) + PeerName.ELLIPSIS, over.name)
        assertNoBrokenPair(over.name)
    }

    @Test
    fun `комбінований знак не лишається без своєї основи`() {
        assertEquals(1, PeerName.length(yi))
        val over = PeerName.accept(yi.repeat(PeerName.MAX + 1))
        assertTrue(over.shortened)
        assertEquals(yi.repeat(PeerName.MAX - 1) + PeerName.ELLIPSIS, over.name)
        // Рівно 64 ЗНАКИ, як їх бачить людина, — а не 127 кодових одиниць, з
        // яких вони складені. Обрив по одиниці лишив би на хвості саму дужку
        // U+0306 без своєї «и».
        assertEquals(PeerName.MAX, PeerName.length(over.name))
        assertEquals(PeerName.MAX - 1, PeerName.length(over.name.dropLast(1)))
    }

    @Test
    fun `прапорець — це один знак, а не дві літери`() {
        assertEquals(1, PeerName.length(flag))
        assertEquals(2, PeerName.length(flag + flag))
        // Непарна третя літера починає СВІЙ прапор, а не доліплюється до
        // попереднього: інакше «🇺🇦🇺» була б одним знаком.
        assertEquals(2, PeerName.length(flag + cp(0x1F1FA)))
    }

    @Test
    fun `скорочене завжди несе «…», ціле — ніколи`() {
        val cases = listOf(
            "",
            "Марта",
            "я".repeat(PeerName.MAX),
            "я".repeat(PeerName.MAX + 1),
            "я".repeat(4000),
            family.repeat(200),
            yi.repeat(100),
        )
        cases.forEach { raw ->
            val out = PeerName.accept(raw)
            assertTrue(
                "скорочення без «…»: «${out.name}» (shortened=${out.shortened})",
                out.shortened == out.name.endsWith(PeerName.ELLIPSIS),
            )
        }
    }

    @Test
    fun `керівні символи й перевертачі рядка не доїжджають`() {
        val nasty = "Пет" + cp(0) + "ро" + "\n" + "Іванов" + cp(0x7F) +
            cp(0x202E) + cp(0x200B) + cp(0x200E)
        val out = PeerName.accept(nasty)
        // Керівний символ ПРИБИРАЄТЬСЯ, а не стає пробілом: він не пробіл, а
        // ніщо. Пробілом стає лише перенос.
        assertEquals("Петро Іванов", out.name)
        assertFalse(out.shortened)
        listOf(0, 0x7F, 0x202E, 0x200B, 0x200E, 0x0A).forEach {
            assertFalse("лишився U+%04X".format(it), out.name.contains(cp(it)))
        }
    }

    @Test
    fun `ZWJ лишається, бо без нього сімʼя розсипається`() {
        assertEquals(family, PeerName.accept(family).name)
    }

    @Test
    fun `самі пробіли — це не імʼя`() {
        assertEquals("", PeerName.accept("     ").name)
        assertEquals("", PeerName.accept(null).name)
        assertEquals("", PeerName.accept(cp(0x200B).repeat(20)).name)
    }

    @Test
    fun `невидиме імʼя — це теж не імʼя`() {
        // Самі зʼєднувачі або самі комбіновані знаки без основи стали б у
        // ростері рядком, у якому не видно НІЧОГО, — а це не порожньо, тож
        // коротка форма ключа на підміну не стала б.
        assertEquals("", PeerName.accept(ZWJ.repeat(20)).name)
        assertEquals("", PeerName.accept(cp(0x0306).repeat(20)).name)
        assertEquals("", PeerName.accept(cp(0xFE0F).repeat(5)).name)
        // А з основою — лишається одним знаком.
        assertEquals(yi, PeerName.accept(yi).name)
        assertEquals("Марта", PeerName.accept(ZWJ + "Марта" + ZWJ).name)
    }

    @Test
    fun `чотири тисячі знаків не валять і не проходять`() {
        val out = PeerName.accept("я".repeat(4000))
        assertTrue(out.shortened)
        assertEquals(PeerName.MAX, PeerName.length(out.name))
    }

    @Test
    fun `імʼя за читаною межею не дочитується — і про це сказано`() {
        // Мутація «підняти RAW_CAP» нічого не ламала, поки не було цього
        // випадку. Кажу як є: RAW_CAP не відкидає ховання за задумом, він
        // обриває ЧИТАННЯ на 4096 — імʼя за цією межею просто не дочитується
        // й виходить порожнім. Межа чесна (64 графеми не бувають довшими), але
        // без прапорця людина побачила б коротку форму ключа й не знала, що
        // імʼя взагалі надсилали.
        val hidden = "\u200B".repeat(5000) + "Марта"
        val out = PeerName.accept(hidden)
        assertEquals("", out.name)
        assertTrue("надіслане було, читаного не лишилось", out.unreadable)
    }

    @Test
    fun `самі керівні знаки — теж імʼя, яке не читається`() {
        // Той самий клас, але без межі роботи: рядок короткий, просто в ньому
        // немає жодного знака, який можна показати.
        assertTrue(PeerName.accept("\u202E\u200E\u0000").unreadable)
    }

    @Test
    fun `порожнього імені ніхто не надсилав — і причини немає`() {
        // Відмова мусить називати те, що сталось. Коли імені не надсилали
        // взагалі, казати «не читається» було б власною вигадкою.
        assertFalse(PeerName.accept("").unreadable)
        assertFalse(PeerName.accept(null).unreadable)
        assertFalse(PeerName.accept("   ").unreadable)
        assertFalse(PeerName.accept("Марта").unreadable)
    }

    @Test
    fun `рядок, довший за читану межу, теж дістає «…»`() {
        // Перші 64 графеми короткі, решта — сто тисяч знаків, які ніхто не
        // читає. Без позначки скорочення це імʼя виглядало б цілим.
        val out = PeerName.accept("Марта" + "я".repeat(200_000))
        assertTrue(out.shortened)
        assertTrue(out.name.endsWith(PeerName.ELLIPSIS))
    }

    @Test
    fun `поле вводу ріже по графемі й без «…»`() {
        val over = family.repeat(PeerName.MAX + 5)
        val capped = PeerName.cap(over)
        assertEquals(PeerName.MAX, PeerName.length(capped))
        assertFalse(capped.endsWith(PeerName.ELLIPSIS))
        assertNoBrokenPair(capped)
        // Коротше за межу поле не чіпає взагалі — навіть пробіл, який людина
        // щойно набрала між двома словами.
        assertEquals("Марта ", PeerName.cap("Марта "))
    }

    @Test
    fun `розбиття на графеми не залежить від BreakIterator`() {
        // Заміряно на JDK 17: java.text.BreakIterator ділив сімʼю на 5, а
        // прапорець на 2. Сторож на розрив емодзі, збудований на ньому, був би
        // зеленим на розірваному емодзі.
        //
        // 06.09 виміряно ще раз, на JDK 21: там BreakIterator уже дає 1 —
        // сегментацію в платформі полагодили. Тест до того дня вимагав рівно
        // 5 і тому ЧЕРВОНІВ на новішій Java: він стеріг не нашу поведінку, а
        // ваду чужої бібліотеки, і ламався, коли ту ваду виправили. Через це
        // ствол не збирався на машині з JDK 21, хоч продукт був справний.
        //
        // Тепер тут два різні твердження, і лише одне з них наше:
        //   1) НАШ лічильник дає 1 — на будь-якій Java. Це вимога.
        //   2) BreakIterator дає щось (5 на JDK 17, 1 на JDK 21) — це
        //      спостереження про платформу, і воно НЕ вимога.
        // Саме тому ми маємо власне розбиття: покладатись на друге не можна,
        // бо воно міняється між версіями Java під нашими ногами.
        val breaker = java.text.BreakIterator.getCharacterInstance()
        breaker.setText(family)
        breaker.first()
        var jdk = 0
        while (breaker.next() != java.text.BreakIterator.DONE) jdk++
        assertTrue(
            "BreakIterator дав $jdk на ${System.getProperty("java.version")} — " +
                "число саме по собі не вада; вадою було б, якби наш лічильник " +
                "почав на нього спиратись",
            jdk >= 1,
        )
        assertEquals("наш лічильник мусить бачити сімʼю як один символ", 1, PeerName.length(family))
    }

    private fun assertNoBrokenPair(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("обірвано сурогатну пару в «$text»", i + 1 < text.length)
                assertTrue(Character.isLowSurrogate(text[i + 1]))
                i += 2
            } else {
                assertFalse("самотній низький сурогат у «$text»", Character.isLowSurrogate(c))
                i++
            }
        }
    }

    private companion object {
        val ZWJ = String(Character.toChars(0x200D))

        fun cp(code: Int): String = String(Character.toChars(code))
    }
}
