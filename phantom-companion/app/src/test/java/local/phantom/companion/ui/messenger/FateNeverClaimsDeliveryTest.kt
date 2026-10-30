package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Доля листа не стверджує доставки.
 *
 * Вузол каже, якою дорогою лист **поїхав**: `direct`, `relay`, `cloud`,
 * `mailbox`, `local-hub`. Про те, чи його **прочитали** або чи він дійшов до
 * рук людини, кадр не знає **нічого** — цього факту в ньому немає взагалі.
 *
 * Межа дослівно від того, хто писав вузол:
 * > `direct` каже, що лист поїхав прямою дорогою, а не що його прочитали.
 * > «Надіслано напряму» — чесно, «Доставлено» — ні.
 *
 * ## Чому це окремий сторож, а не просто акуратність
 *
 * «Доставлено» — найлегша брехня в месенджері: вона звучить як технічний факт,
 * її ніхто не переперевіряє, і вона зʼявиться саме тоді, коли хтось
 * «покращуватиме копію» через місяць. Подвійні галочки й «прочитано» — того ж
 * роду: обидва стверджують чуже знання, якого в нас немає.
 *
 * ## Чому він більше не тримається за `msgr_fate_*`
 *
 * 06.09 родина `msgr_fate_*` пішла: девʼять рядків, нуль викликів у продукті —
 * вони лишились від вузлового месенджера, який поїхав із застосунку. Привʼязка
 * до одного імені ресурсу і є те, чим сторож тихо стає декорацією: ресурс
 * перейменували — і зелене вже ні про що. Тому стережеться **родина `msgr_*`**:
 * усі слова, якими месенджер говорить про лист, у ОБОХ мовах.
 *
 * Не ловиться: та сама брехня літералом у коді — на неї є вартовий кириличних
 * літералів, — і вона ж під імʼям поза родиною `msgr_*`. Свідомо НЕ береться
 * родина `peer_*`: там живе `peer_mark_read` = «Прочитано», і це ВЛАСНА дія
 * оператора («позначити прочитаним»), а не твердження про чуже знання.
 * Не беруться й літерали `WebMessengerBridge`: `"delivered"` там — поле дроту
 * й імʼя стану, яких людина не бачить.
 *
 * GATE LAW: читає файли як текст. Входи задачі оголошені (30.08).
 */
class FateNeverClaimsDeliveryTest {

    /**
     * Слова, що стверджують ЧУЖЕ знання: чи дійшло, чи прочитано. Кадр цього
     * не несе, тож жодне з них не має права стояти в словах про лист.
     */
    private val forbidden = listOf(
        "доставлен", "вручен", "прочитан", "переглянут",
        "delivered", "read by", "seen by", "was read",
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun messengerStrings(locale: String): Map<String, String> {
        val f = File(repoRoot(), "app/src/main/res/$locale/strings.xml")
        assertTrue("сторож осліп: немає $locale/strings.xml", f.isFile)
        return Regex("""<string name="(msgr_[^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Спершу — чи сторож узагалі має що стерегти. */
    @Test
    fun `словник месенджера існує в обох мовах`() {
        val uk = messengerStrings("values-uk")
        val en = messengerStrings("values")
        assertTrue("рядків msgr_* немає — сторож нічого не боронить", uk.isNotEmpty())
        assertEquals(
            "словник месенджера розійшовся між мовами: англійський телефон побачить українське слово",
            uk.keys,
            en.keys,
        )
    }

    /**
     * І чи вміє він почервоніти на тому, що ми забороняємо.
     *
     * Рідкісна річ і навмисна: сторожеві згодовується завідома брехня, і він
     * мусить її зловити. Без цього його зелене не відрізнити від зеленого
     * сторожа, який просто нічого не читає.
     */
    @Test
    fun `сторож червоніє на слові, якого ми не кажемо`() {
        val sample = mapOf("msgr_fate_direct" to "Доставлено адресату")
        val caught = sample.filterValues { v -> forbidden.any { v.lowercase().contains(it) } }
        assertEquals(
            "сторож не бачить найлегшої брехні месенджера — тоді його зелене нічого не варте",
            1,
            caught.size,
        )
    }

    @Test
    fun `жодне слово месенджера не стверджує доставки чи прочитання`() {
        val offenders = (
            messengerStrings("values-uk") + messengerStrings("values").mapKeys { "en:${it.key}" }
            )
            .filterValues { v -> forbidden.any { v.lowercase().contains(it) } }
            .map { "${it.key} = «${it.value}»" }
        assertEquals(
            "месенджер стверджує те, чого кадр не знає: $offenders.\n" +
                "Вузол каже, якою дорогою лист ПОЇХАВ, і нічого — про те, чи його " +
                "прочитали. «Надіслано напряму» чесно, «Доставлено» — ні.",
            emptyList<String>(),
            offenders,
        )
    }
}
