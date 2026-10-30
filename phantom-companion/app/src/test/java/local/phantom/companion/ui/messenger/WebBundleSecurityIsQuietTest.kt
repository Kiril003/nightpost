package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * БЕЗПЕКА МОВЧИТЬ, ПОКИ ВСЕ ГАРАЗД.
 *
 * Вимір 20.09, збірка d169a610, апарат A52. Месенджер показував людині власну
 * механіку замість того, щоб робити її мовчки:
 *
 *   Налаштування → «Мережа & P2P» — «Гібридна P2P / Серверна архітектура»,
 *     «Дороги дзвінка: без STUN і TURN», абзац про NAT і TURN, «КЛЮЧ ЦЬОГО
 *     ВУЗЛА: 3b986775dda160cf», «Відбиток ключа: 3034 8905 …», 60-значне число
 *     безпеки, «Порівняти QR-кодом», «Що робити, якщо розійшлося?» — і, серед
 *     цього, «Пряма адреса: 158.196.237.8:44997»: публічна IP з портом
 *     відкритим текстом;
 *   Екран запрошення — «Адреса вашого вузла: 158.196.227.148:36127»;
 *   Довгий дотик по листу — «Інфо / P2P» серед сімнадцяти дій;
 *   Меню розмови — «Звірка співрозмовника» і «Показати число безпеки», два
 *     пункти, що кликали один і той самий openSheet().
 *
 * Правило власника: безпека говорить лише тоді, коли людина мусить УХВАЛИТИ
 * РІШЕННЯ. Не «ось ваш код звірки» — а «це не той співрозмовник, що вчора».
 * Механіка лишилась на місці й працює; з очей вона зникла.
 *
 * ЧОМУ СТОРОЖ ЧИТАЄ АРТЕФАКТ. Веб живе в сусідньому репозиторії, а в APK їде
 * блоб `assets/webmsgr`, який кладуть окремим кроком. Дерево буває чистим, а
 * блоб — на десять комітів позаду; так уже було. Тому кожне твердження нижче
 * читає саме ті `.js`, що поїдуть до людини.
 *
 * ЧОГО ЦЕЙ СТОРОЖ НЕ ДОВОДИТЬ. Він читає текст, а не кадр. [`прибране не
 * повернулось`] — про рядки, знесені геть з усього бандла, і воно сильне.
 * [`налаштування телефона показують один рядок стану і жодної механіки`] —
 * про тіло одного компонента (межі — сусідні `({isOpen:` у чанку), чинне
 * рівно настільки, наскільки мініфікатор тримає компонент суцільним. Відбиток
 * свідомо лишився в повному профілі й у довіднику — там його відкривають
 * рукою. Живий доказ — на склі.
 */
class WebBundleSecurityIsQuietTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val bundle get() = File(repoRoot(), "app/src/main/assets/webmsgr")

    private fun texts(): List<Pair<String, String>> =
        bundle.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".js") || it.name.endsWith(".html") || it.name.endsWith(".css")) }
            .map { it.name to it.readText() }
            .toList()
            .also {
                check(it.size > 5) { "у ${bundle.path} менше шести текстових файлів (${it.size}) — бандла нема, і зелене нічого не варте" }
            }

    private fun countOf(needle: String, files: List<Pair<String, String>> = texts()): Int = files.sumOf { (_, t) ->
        var n = 0
        var i = t.indexOf(needle)
        while (i >= 0) { n++; i = t.indexOf(needle, i + 1) }
        n
    }

    /**
     * Знесене геть — і чому саме геть, а не за перемикач. Порожня причина тут
     * не приймається: виняток без причини наступний читач прийме за недогляд.
     */
    private val gone: Map<String, String> = mapOf(
        "Пряма адреса" to
            "публічна IP співрозмовника з портом, відкритим текстом, на екрані, який показують і фотографують; рішення за нею немає жодного — лишився наслідок словом («прямий звʼязок: є»)",
        "Адреса вашого вузла" to
            "те саме про власну адресу на екрані запрошення; тепер «Як до вас дійде лист» — прямо чи через сховок",
        "Так вас видно ззовні" to
            "підпис під тією самою власною IP",
        "Інфо / P2P" to
            "протокол у назві пункту меню; це «Подробиці листа»",
        "Звірка співрозмовника" to
            "один із двох пунктів меню розмови, що кликали той самий openSheet(); лишився один — «Картка співрозмовника»",
        "Показати число безпеки" to
            "другий із тих двох",
        "Звірити число безпеки" to
            "той самий пункт в іншому стані",
        "Ключ змінився — звірте наново" to
            "подія, сказана механікою; тепер «Це вже інший апарат — перевірте»",
        "Мережа & P2P" to
            "назва вкладки й усі вказівники на неї; тепер «Зʼєднання»",
        "/messenger/exit" to
            "дорога, якої міст не несе свідомо: кнопка «вийти» просила людину зробити роботу застосунку системною кнопкою «назад»",
        "Вийти з месенджера" to
            "та сама кнопка",
        "Цей браузер системних сповіщень не має" to
            "правда про WebView і брехня про продукт: сповіщення показує сам застосунок (PeerNotifications), і дозвіл уже наданий",
        "Прослухати (TTS)" to
            "абревіатура в підписі кнопки",
        "Видалити для всіх учасників" to
            "вибір, якого на телефоні не існує: обидві ручки мосту стирають локально й відповідають for_everyone:false",
        "Видалити тільки для мене" to
            "другий пункт того ж неіснуючого вибору",
        // 24.09: настільна модалка налаштувань у телефонний бандл не їде
        // (rootModals.ts, __PHONE_BUILD__) — механіку не сховали, а відняли.
        "data-plumbing-toggle" to
            "перемикач «Технічні подробиці», за яким лежала механіка мережі; на телефоні ховати більше нічого",
        "Гібридна P2P" to
            "опис архітектури замість стану; у «Налаштуваннях» тепер одне слово: напряму / через сховок",
        "Дороги дзвінка" to
            "технічні подробиці дзвінка — не рішення людини",
        "Листи запечатані між вузлами" to
            "пояснення шифру, яке нічого не просить ухвалити",
    )

    @Test
    fun `прибране не повернулось у бандл телефона`() {
        check(gone.values.none { it.isBlank() }) { "заборона без причини — наступний читач прийме її за недогляд" }
        val back = gone.keys.filter { countOf(it) > 0 }.sorted()
        assertEquals(
            "у бандлі телефона знову є механіка безпеки перед очима (${back.size}):\n    " +
                back.joinToString("\n    ") { "$it — прибрано бо: ${gone[it]}" },
            emptyList<String>(),
            back,
        )
    }

    /**
     * Тіло телефонних «Налаштувань»: від пропсів компонента з
     * `testId:"phone-settings"` до наступного компонента-модалки в тому ж чанку.
     */
    private fun settingsSheet(files: List<Pair<String, String>>): Pair<String, String> {
        // Позначка — проп FormSheet (`testId:`) або, з 26.09 (екран «Я»), атрибут кореня налаштувань.
        val marks = listOf("testId:\"phone-settings\"", "\"data-testid\":\"phone-settings\"")
        val (name, text) = files.firstOrNull { (_, t) -> marks.any { t.contains(it) } }
            ?: error("у бандлі нема аркуша «Налаштування» (testId phone-settings) — сторож нічого не перевірив")
        val mark = marks.map { text.indexOf(it) }.filter { it >= 0 }.min()
        val props = Regex("""\(\{isOpen:""")
        val start = props.findAll(text).map { it.range.first }.lastOrNull { it < mark }
            ?: error("у $name не знайшов початку компонента налаштувань")
        val end = props.findAll(text).map { it.range.first }.firstOrNull { it > mark } ?: text.length
        return name to text.substring(start, end)
    }

    private val mechanics = listOf(
        Regex("""\b(STUN|TURN|NAT|ICE|SDP|WebRTC|P2P)\b"""),
        Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b"""),
        Regex("""[Кк]люч"""),
        Regex("""[Вв]ідбиток"""),
        Regex("""[Чч]исло безпеки"""),
        Regex("""data-plumbing-toggle|Технічні подробиці"""),
    )

    /** Порожній список — обіцянка тримається; інакше — що саме вилізло перед очі. */
    private fun settingsExposes(files: List<Pair<String, String>>): List<String> {
        val (name, sheet) = settingsSheet(files)
        val out = mutableListOf<String>()
        val states = Regex("""data-connection-state""").findAll(sheet).count()
        if (states != 0) out += "рядок стану зʼєднання повернувся ($name)"
        for (r in mechanics) r.findAll(sheet).forEach { out += "«${it.value}» ($name)" }
        return out
    }

    /**
     * Механіка мережі в «Налаштуваннях» телефона не лежить навіть за перемикачем:
     * з 26.09 (власник: «навіщо мені чути про відбитки… показувати щось гарне»)
     * немає навіть рядка «Листи — напряму / через сховок», а ключа, відбитка,
     * адреси й протоколів в аркуші немає зовсім.
     */
    @Test
    fun `налаштування телефона показують один рядок стану і жодної механіки`() {
        assertEquals(
            "у «Налаштуваннях» телефона знову механіка мережі перед очима",
            emptyList<String>(),
            settingsExposes(texts()),
        )
    }

    /**
     * Звірка озивається на подію, а не стоїть стіною з 60 цифр.
     *
     * Тихий вхід («Переконатися…») і гучна подія («Це не той апарат…») — два
     * різні стани того самого блоку; якщо котрогось у бандлі немає, значить
     * [local.phantom.companion.ui.messenger] знову показує число завжди або не
     * показує ніколи.
     */
    @Test
    fun `звірка має тихий вхід і гучну подію`() {
        for ((what, s) in listOf(
            "тихий вхід" to "Переконатися, що це справді він",
            "подія зміни апарата" to "Це не той апарат, що був раніше",
        )) {
            assertTrue("у бандлі нема $what («$s»)", countOf(s) > 0)
        }
    }

    /** Аркуш дій відкривається коротким, а видалення називає свій наслідок. */
    @Test
    fun `аркуш дій і видалення кажуть, що зроблять`() {
        assertTrue(
            "у бандлі нема перемикача «ще» (data-msg-more) — аркуш знову відкривається всіма сімнадцятьма діями",
            countOf("data-msg-more") > 0,
        )
        assertTrue(
            "кнопка видалення не називає наслідок («Видалити в себе») — людина знову тисне «Видалити» наосліп",
            countOf("Видалити в себе") > 0,
        )
        assertTrue(
            "у діалозі видалення нема сказаного словом наслідку (data-delete-scope-fixed)",
            countOf("data-delete-scope-fixed") > 0,
        )
    }

    /**
     * Прокрутка, якої нема, і порожнеча, яка мовчала.
     *
     * Ряд вкладок, що гортався без натяку, жив у настільній модалці; телефонні
     * «Налаштування» — один аркуш без вкладок. Якщо ряд повернеться, він мусить
     * переноситися (`.msg-tabs`), а не ховати половину вкладок за краєм.
     * Заставка лежить у самому документі: її видно з першого кадру, ще до того,
     * як приїде модуль.
     */
    @Test
    fun `вкладки не ховаються за краєм, а порожній екран каже, що він живий`() {
        assertEquals(emptyList<String>(), hiddenTabRows(texts()))

        val index = File(bundle, "index.html").readText()
        check(index.contains("id=\"root\"")) { "в index.html нема кореня — сторож читає не той файл" }
        assertTrue(
            "в index.html нема заставки (id=\"boot\") — девʼять секунд порожнечі повернулись",
            index.contains("id=\"boot\""),
        )
        assertTrue(
            "заставка мовчить — нема рядка, що застосунок живий",
            index.contains("Відкриваю месенджер"),
        )
        // Заставка мусить лежати ВСЕРЕДИНІ кореня: саме тому React змітає її
        // першим коммітом, і прибирати руками нема чого.
        val root = index.indexOf("id=\"root\"")
        val boot = index.indexOf("id=\"boot\"")
        val close = index.indexOf("</div>", boot)
        assertTrue("заставка стоїть поза коренем — її ніхто не прибере", root in 0 until boot && close > boot)
        // Скрипта в ній бути не може: це перше, що бачить людина, і воно мусить
        // малюватись без жодного модуля.
        val head = index.substring(boot, close)
        assertTrue("заставка несе скрипт — вона перестала бути тим, що видно з першого кадру", !head.contains("<script"))
    }

    private fun hiddenTabRows(files: List<Pair<String, String>>): List<String> {
        val out = mutableListOf<String>()
        val (name, sheet) = settingsSheet(files)
        if (Regex(""""?role"?\s*:\s*"tab(list)?"|msg-tabs""").containsMatchIn(sheet)) {
            out += "у «Налаштуваннях» телефона знову ряд вкладок ($name)"
        }
        val rows = files.filter { it.first.endsWith(".js") }.sumOf { (_, t) -> Regex("""msg-strip msg-tabs""").findAll(t).count() }
        val wraps = files.filter { it.first.endsWith(".css") }
            .any { (_, t) -> Regex("""\.msg-tabs\s*\{[^}]*flex-wrap:\s*wrap""").containsMatchIn(t) }
        if (rows > 0 && !wraps) out += "ряд вкладок .msg-tabs ($rows) без правила перенесення — гортається без натяку"
        return out
    }

    /**
     * Сторож уміє почервоніти.
     *
     * Без цього обидва твердження вище зелені з найгіршої причини — сліпі:
     * [countOf] на помилковому корені дає нуль для всього, і заборони
     * справджуються самі собою.
     */
    @Test
    fun `сторож уміє почервоніти`() {
        // Рядка, якого нема, не знаходиться.
        assertEquals(0, countOf("такого рядка в бандлі немає ніколи"))
        // А той, що є, — знаходиться, і не один раз.
        assertTrue("сторож не бачить власного бандла", countOf("/messenger/conversations") > 0)
        assertTrue("сторож не бачить розмітки бандла", countOf("data-settings-read-receipts") > 0)

        // Мутанти — на копіях бандла в памʼяті; кожен мусить почервоніти.
        val real = texts()
        val sheetFile = settingsSheet(real).first
        fun mutate(f: (String) -> String) = real.map { (n, t) -> n to if (n == sheetFile) f(t) else t }
        val wall = listOf("testId:\"phone-settings\",", "\"data-testid\":\"phone-settings\",")
            .first { w -> real.any { (_, t) -> t.contains(w) } }
        val mutants = mapOf(
            "ключ вузла в аркуші" to mutate { it.replace(wall, "$wall\"aria-label\":\"Ключ цього вузла\",") },
            "адреса в аркуші" to mutate { it.replace(wall, "$wall\"title\":\"158.196.237.8:44997\",") },
            "механіка за перемикачем" to mutate { it.replace(wall, "$wall\"data-plumbing-toggle\":!0,") },
            "стан зʼєднання повернувся" to mutate { it.replace(wall, "$wall\"data-connection-state\":\"direct\",") },
        )
        mutants.forEach { (name, files) ->
            check(files != real) { "мутант «$name» холостий — копія не змінилась" }
            assertTrue("мутант «$name» пройшов", settingsExposes(files).isNotEmpty())
        }
        val tabs = mutate { it.replace(wall, "$wall\"role\":\"tablist\",") }
        assertTrue("мутант «ряд вкладок» пройшов", hiddenTabRows(tabs).isNotEmpty())
        val back = real.map { (n, t) -> n to if (n == sheetFile) "$t\"Гібридна P2P\"" else t }
        assertTrue("мутант «повернута механіка» пройшов", countOf("Гібридна P2P", back) > 0)
    }
}
