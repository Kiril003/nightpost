package local.phantom.companion.feature.stream.ui.people

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Хиби, яких не спіймав жоден тест, бо всі видно лише оком.
 *
 * «в мережі · 0» поруч із пʼятьма контактами читалося як поломка, хоча нуль тут
 * — звичайний стан поля. Дві постійні кнопки на всю ширину відсували перший
 * рядок реєстру на 230dp від верху. Кожен контакт сидів у власній коробці.
 *
 * Це перевірки ФОРМИ, а не пікселів: вони не доводять, що екран гарний. Вони
 * падають тієї миті, коли повертається гола цифра в спокійному стані, коробка
 * навколо рядка або картка на всю ширину.
 *
 * ## 06.09 — що тут зникло разом із підметом
 *
 * Екран перестав бути списком розмов: `PeopleScreen` став `PeerKeysScreen` —
 * люди та їхні ключі, а листи живуть в одному місці, у вебі. З ним пішли
 * чотири перевірки, і кожна саме тому, що ЇЇ ПІДМЕТА більше немає:
 *
 *  - «дві дії живуть в одному «+»» — «+» знято навмисно: камера за кнопкою й
 *    аркушем коштувала два дотики біля людини, яка вже дістала телефон. Замість
 *    неї — `HandshakeBar`, постійний рядок; правило перевернулось, і зберігати
 *    стару перевірку означало б стерегти рішення, яке скасували. На її місці
 *    нижче стоїть перевірка нової форми.
 *  - «нова група пропонується лише коли є кого групувати» — груп на цьому
 *    екрані немає зовсім.
 *  - «колонку обовʼязку справді питають» — рядок реєстру більше не показує
 *    обовʼязку. УВАГА: `PeopleBoard.duty()` і `PeopleBoard.undelivered()`
 *    лишились у дереві БЕЗ ЖОДНОГО ВИКЛИКАЧА, а `people_duty_undelivered` і
 *    `people_duty_your_turn` — у ОБОХ locale без жодного читача. Це та сама
 *    «написане й не викликане», заради якої перевірку колись і писали; ліки —
 *    у `src/main` (прибрати або підʼєднати), не тут.
 *  - ««+» сидить унизу, чисто від спільного хрому» — кнопки внизу немає;
 *    відступ під острів на корені колонки стереже `BottomChromeInsetFirewallTest`.
 *
 * Ще дві перевірки читали `PeerChatScreen.kt` (аркуш дій над листом і час
 * наступної спроби в деталях листа). Файла немає — рідний екран розмови пішов
 * з застосунку. `peer_detail_next` лишився рядком в обох locale без читача.
 *
 * ## 04.10 — `PeopleScreen.kt` пішов цілком
 *
 * `PeerKeysScreen` не складав жоден маршрут ще з 02.10 (оболонка Чати · Мапа ·
 * Профіль); він і `PeerHandshakeSheet` тримали лише одне одного. З ними пішли
 * чотири перевірки ФОРМИ того екрана — рядок знайомства (`HandshakeBar`), код
 * у порожньому реєстрі, слова реєстру ключів і шапка без кнопок. Їхнього
 * підмета більше немає; людей і знайомство малює веб-месенджер. Рядки
 * `keys_*`, `chatlist_add_person`, `chatlist_scan_code`, `chatlist_title`,
 * `people_invite_first_body` лишились без читача — прибирати їх власнику
 * `strings*.xml`. Відтінок обличчя тепер стережеться там, де його малюють:
 * `PeerFace.PeerFaceDisc` (тон людини з PersonTone, як у вебі й на мапі).
 */
class PeopleSurfaceTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "repo root with feature-stream/ not found" }
    }

    private fun source(name: String): String {
        val f = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/$name",
        )
        assertTrue("missing $name", f.isFile)
        return f.readText()
    }


    /** Everything between the first `{` after [head] and its matching `}`. */
    private fun blockAfter(source: String, head: String): String {
        val at = source.indexOf(head)
        assertTrue("«$head» is gone from the source", at >= 0)
        val open = source.indexOf('{', at)
        assertTrue("no body opens after «$head»", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            if (source[i] == '{') depth++
            if (source[i] == '}' && --depth == 0) break
            i++
        }
        assertTrue("unbalanced braces after «$head»", depth == 0)
        return source.substring(open, i)
    }

    private fun strings(dir: String, file: String = "strings.xml"): Map<String, String> {
        val f = File(repoRoot(), "feature-stream/src/main/res/$dir/$file")
        assertTrue("${f.path} is missing", f.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = LinkedHashMap<String, String>()
        val kids = doc.documentElement.childNodes
        for (i in 0 until kids.length) {
            val el = kids.item(i) as? Element ?: continue
            val name = el.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
            out[name] = el.textContent
        }
        return out
    }

    /** Відтінок плитки мусить іти з ключа, а не з вигадки екрана. */
    @Test
    fun `the key-derived tint is actually drawn`() {
        // 04.10: живе обличчя людини малює PeerFace (картка), не PeopleScreen,
        // і тон береться з тієї самої таблиці, що в месенджері й на мапі
        // (PersonTone за id вузла) — людину впізнають за одним кольором усюди.
        val face = blockAfter(source("PeerFace.kt"), "internal fun PeerFaceDisc(")
        assertTrue(
            "обличчя мусить питати тон людини за її ключем, а не власну вигадку",
            face.contains("PersonTone.index(peerId)") && face.contains("c.person["),
        )
    }

    /** Два «Олеги» — справжній і підроблений — мусять різнитись до цифр. */
    @Test
    fun `the tint follows the key, not the name`() {
        val same = "Олег"
        assertEquals(
            "одне імʼя на двох ключах не сміє давати один відтінок",
            2,
            setOf(PeopleBoard.hue("a1b2c3d4$same"), PeopleBoard.hue("9f8e7d6c$same")).size,
        )
        assertEquals(
            "той самий ключ мусить давати той самий відтінок між запусками",
            PeopleBoard.hue("a1b2c3d4"),
            PeopleBoard.hue("a1b2c3d4"),
            0.0f,
        )
        PeopleBoard.hue("").let {
            assertTrue("відтінок мусить лишатись у межах кола", it >= 0f && it < 360f)
        }
    }

    /** Скільки саме зникне — питання, на яке відповідають ДО дотику. */
    @Test
    fun `перед стиранням розмови стоїть число і межі`() {
        val clear = source("PeerClearUi.kt")
        val block = blockAfter(clear, "internal fun ClearBlock(")
        assertTrue("превʼю зникло з блоку стирання", block.contains("preview"))
        assertTrue(
            "скасувати мусить лишатись одним дотиком, поки стерти — двома",
            block.contains("onCancel"),
        )
        listOf("values-uk", "values").forEach { dir ->
            val text = strings(dir)["peer_clear_preview"]
                ?: fail("$dir/strings.xml is missing peer_clear_preview") as Nothing
            assertTrue(
                "$dir: превʼю мусить нести і число, і проміжок дат",
                text.contains("%1\$d") && text.contains("%2\$s"),
            )
        }
    }

    @Test
    fun `both locales say nobody without printing a count`() {
        val key = "people_filter_reachable_none"
        listOf("values-uk", "values").forEach { dir ->
            val text = strings(dir)[key]
            assertTrue("$dir/strings.xml is missing $key", text != null)
            assertFalse(
                "$dir/$key must not take a count — it exists to avoid printing one",
                text!!.contains('%'),
            )
        }
    }
}
