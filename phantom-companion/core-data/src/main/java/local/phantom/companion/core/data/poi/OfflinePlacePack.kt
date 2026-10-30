package local.phantom.companion.core.data.poi

import java.io.BufferedReader

/**
 * ВОДА — the national place corpus: what a person needs when the network is
 * gone and they are not in Kyiv.
 *
 * WHAT THIS FIXES. Measured 06.09.2026 on the asset that shipped in the APK:
 * [OfflinePoiPack]'s `poi.json` held 21 335 places in bbox
 * [50.21, 30.24, 50.59, 30.83] — Kyiv, and 21 333 of those inside the city.
 * The same OSM extract the road pack is baked from holds 13 461 water points
 * across the country. A person in Kharkiv asking «де вода» got nothing, and
 * the reason was never that the data was missing — it was never carried.
 *
 * WHY IT IS A STREAM AND NOT A SECOND `poi.json`. [OfflinePoiPack] parses its
 * asset whole and holds every row as a live object for the life of the app,
 * which is the right trade for a corpus it keeps querying at ~21 000 rows and
 * the wrong one at ~108 000: measured, the national tier is 23.5 MB of JSON,
 * and the existing 4 MB parse already costs 300–700 ms and ~20 MB of transient
 * allocation on a 2 GB phone. So this takes exactly the shape
 * [OfflineStreetPack] already proved at 270 000 rows — a line per place,
 * streamed into the SAME `poi_gazetteer` table in fixed chunks, nothing
 * retained beyond the chunk in flight. Offline search reads that table, so
 * «де вода» is answered by machinery that already exists.
 *
 * TWO THINGS THIS CORPUS CARRIES THAT THE CITY PACK REFUSED TO.
 *
 * 1. NAMELESS PLACES. `poi.json` drops anything without a name — right for a
 *    café, fatal for water: a spring or a well almost never has one. Here a
 *    row's [name] may be the Ukrainian noun for what the tag says the object
 *    IS («Джерело» for `natural=spring`). That is a translation of the tag,
 *    not an invented name, and [PoiVocabulary] indexes the same words, so the
 *    typed «вода» reaches it either way.
 *
 * 2. FORDS, AS FACTS AND NOT AS PROMISES. `ford=*` is on ~23 000 NODES across
 *    the country against ~2 900 ways, so a ford is a point, and a point is
 *    what a map can honestly draw: HERE is a marked crossing. Whether anything
 *    gets through depends on the water level, which OSM does not carry and
 *    which changes within an hour of rain. Nothing downstream of this file may
 *    turn «Брід» into «проїзний» — the line the project already drew on ice.
 *
 * ────────────────────────────────────────────────────────────────────────
 * ТРЕТЄ, ДОДАНЕ 06.09 І ДОРОЖЧЕ ЗА ПЕРШІ ДВА: МІСЦЕ МАЄ ОСОБУ.
 *
 * Перша редакція везла пʼять колонок і жодного номера OSM, тож id місця був
 * `ID_BASE + номер рядка у файлі`. Це властивість ФАЙЛУ, не місця, і з неї
 * випливали дві вади, обидві мовчазні:
 *
 *   - Прикріпити до місця факт («у цієї аптеки є інсулін», «зачинено»,
 *     «години такі») не було до чого. Ключа, який пережив би перепікання,
 *     просто не існувало.
 *   - Перше ж перепікання з іншим порядком рядків переставило б УСІ номери.
 *     Вже прикріплені факти опинились би на чужих місцях — без винятку, без
 *     помилки, без сліду в журналі.
 *
 * ЗМІРЯНО НА СПРАВЖНЬОМУ АСЕТІ, 06.09, усі 176 558 рядків. Достатньо, щоб у
 * країні змапили ОДНЕ нове місце — тобто достатньо наступного витягу OSM:
 * 176 376 місць зі 176 558 (99.9%) отримали номер, який тепер вказує на чуже
 * місце. Факт «тут є інсулін», причеплений до «Подорожника» на
 * (45.341785, 28.840941), поїхав на «Аптеку оптових цін» за 200 метрів —
 * тобто на іншу аптеку, достатньо схожу, щоб цього ніхто не помітив. Із
 * шостою колонкою те саме перепікання зсуває НУЛЬ рядків.
 *
 * Тепер шоста колонка `ref` (`n123` / `w456` / `r789`) везе саме те, що піч
 * уже тримала в руці й викидала, а id походить від неї ([idForRef]). Перепік
 * більше не рухає жодного рядка. Той самий `ref` лягає в
 * [PoiGazetteerEntity.tags] під ключем [REF_TAG] — і живий шлях Overpass
 * ставить його теж, тож аптека з довідника й та сама аптека, побачена
 * живою, нарешті впізнаються як одне місце.
 *
 * ЧОГО ЦЕ НАВМИСНЕ НЕ РОБИТЬ: не зводить ці два рядки в один первинний ключ.
 * `PoiGazetteerDao.upsert` пише через `REPLACE`, тож спільний ключ означав би,
 * що чергове засівання довідника (теги порожні) ЗАТРЕ теги, набрані живими
 * відповідями — години, телефон, поверх. Дублікат видно і без цього:
 * `searchOfflineVerdict` уже звужує список, і робить це тепер за [REF_TAG].
 */
object OfflinePlacePack {

    /**
     * Synthetic ids start here — clear of every OSM id in use at bake time and
     * of [OfflineStreetPack.ID_BASE] by a whole order of magnitude, so a place
     * from this corpus can never overwrite a street or a place the gazetteer
     * saw live.
     */
    internal const val ID_BASE = 800_000_000_000_000L

    /**
     * СКІЛЬКИ МІСЦЯ ВІДДАНО ОДНОМУ ВИДУ OSM — і чому саме 10^13.
     *
     * Найбільший вузол OSM на сьогодні має номер порядку 1.3·10^10, лінія —
     * 1.4·10^9, відношення — 2·10^7. 10^13 дає вузлам 750-кратний запас, тобто
     * кілька десятиліть зростання, і при цьому всі ЧОТИРИ коридори
     * (вузол/лінія/відношення/порядковий) вкладаються в 4·10^13 — вдесятеро
     * менше за проміжок 10^14 до [OfflineStreetPack.ID_BASE]. Вулиця лишається
     * недосяжною, як і була.
     */
    internal const val KIND_SPAN = 10_000_000_000_000L

    private const val KIND_NODE = 0L
    private const val KIND_WAY = 1L
    private const val KIND_RELATION = 2L

    /**
     * Коридор для рядків СТАРОГО, пʼятиколонкового файлу — тих, що не мають
     * `ref` і тому досі нумеруються порядком рядка.
     *
     * Він окремий не з охайності. Порядковий номер 5 і вузол OSM №5 — це два
     * різні місця, а в одному коридорі вони отримали б однаковий id і тихо
     * перезаписали одне одного. Розвівши їх, ми лишили порядковій нумерації
     * рівно ту область, де вона нікому не заважає, і зробили різницю між
     * «сталий id» і «номер рядка» видимою в самому числі.
     */
    private const val KIND_ORDINAL = 3L

    /**
     * Ключ у [PoiGazetteerEntity.tags], під яким рядок несе свою особу в OSM.
     *
     * Підкреслення попереду — бо це НЕ тег OSM: жоден тег так не зветься, і
     * жодна поверхня, що читає теги за іменем (`opening_hours`, `fuel:*`,
     * `socket:*`), його не зачепить. Той самий ключ ставить і живий шлях
     * Overpass ([PoiRepository]), і саме тому місце з довідника й те саме
     * місце, побачене живим, нарешті можна впізнати як одне.
     */
    const val REF_TAG = "_ref"

    /**
     * `n123` / `w456` / `r789` → сталий id газетира, або `null`, якщо рядок не
     * несе розпізнаваного посилання.
     *
     * ЦЕ І Є ЛІКИ. Доти id місця був `ID_BASE + номер рядка`, тобто властивість
     * ФАЙЛУ, а не місця: перше ж перепікання з іншим порядком рядків мовчки
     * переставило б усі номери, і будь-який прикріплений факт («у цієї аптеки
     * є інсулін», «зачинено до понеділка») опинився б на чужій аптеці. Без
     * помилки, без сліду, без способу помітити. Тепер номер походить від
     * обʼєкта OSM, і порядок рядків не значить нічого.
     *
     * `null` повертається не лише на сміття, а й на номер, що не влазить у
     * [KIND_SPAN]: якщо OSM колись перевалить за 10^13, краще відкотитись до
     * порядкової нумерації (видимо, у своєму коридорі), ніж дати номеру
     * перелитись у сусідній вид і зіткнутися.
     */
    /**
     * `("node", 123)` → `"n123"` — рівно та форма, яку пише піч, щоб живий
     * шлях Overpass і довідник називали один обʼєкт ОДНАКОВО.
     *
     * `type` у [local.phantom.companion.core.net.map.OverpassElement] необовʼязковий
     * саме тому, що відповідь може прийти від підробленого чи скороченого
     * джерела; тоді особи немає, і краще її не мати, ніж вигадати вид.
     *
     * УВАГА, ЗВʼЯЗОК НЕСИМЕТРИЧНИЙ І ЦЕ НАВМИСНО. Для рядка ДОВІДНИКА
     * [REF_TAG] означає ще й «id виведений із цього посилання» ([idForRef]).
     * Для рядка, побаченого ЖИВИМ, id лишається сирим номером OSM, як і був:
     * міняти його означало б осиротити все, що газетир уже накопичив на
     * пристроях. Спільним у двох шляхів є сам рядок [REF_TAG] — і цього
     * досить, щоб упізнати одне місце у двох рядках.
     */
    fun osmRef(type: String?, osmId: Long): String? {
        if (osmId <= 0L) return null
        return when (type) {
            "node" -> "n$osmId"
            "way" -> "w$osmId"
            "relation" -> "r$osmId"
            else -> null
        }
    }

    internal fun idForRef(ref: String): Long? {
        if (ref.length < 2) return null
        val kind = when (ref[0]) {
            'n' -> KIND_NODE
            'w' -> KIND_WAY
            'r' -> KIND_RELATION
            else -> return null
        }
        val osmId = ref.substring(1).toLongOrNull() ?: return null
        if (osmId <= 0L || osmId >= KIND_SPAN) return null
        return ID_BASE + kind * KIND_SPAN + osmId
    }

    /** The bake epoch off the header line, `generated=<epoch> count=<n>`. */
    fun parseGenerated(header: String): Long =
        HEADER_GENERATED.find(header)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /**
     * Chunk the corpus for [PoiGazetteerDao.upsert], reading forward from
     * [reader] — the header line must already be consumed by the caller, which
     * is what lets a re-seed check cost one `readLine()` instead of a full pass.
     *
     * Never throws on a malformed row: a line that does not parse is skipped,
     * not a reason to abandon the hundred thousand that do.
     */
    fun chunkLines(reader: BufferedReader, chunkSize: Int = 500): Sequence<List<PoiGazetteerEntity>> = sequence {
        var buffer = ArrayList<PoiGazetteerEntity>(chunkSize)
        var idx = 0L
        while (true) {
            val line = reader.readLine() ?: break
            parseLine(line, idx)?.let(buffer::add)
            idx++
            if (buffer.size >= chunkSize) {
                yield(buffer)
                buffer = ArrayList(chunkSize)
            }
        }
        if (buffer.isNotEmpty()) yield(buffer)
    }

    /**
     * `lat \t lon \t cat \t sub \t ref \t name` — шість колонок через табуляцію.
     *
     * ЧОМУ `ref` СТОЇТЬ ПЕРЕД `name`. Назва — єдине поле довільної форми, тож
     * вона мусить лишатись ХВОСТОМ рядка: `limit = 6` віддає все після пʼятої
     * табуляції одним шматком, і навіть якби піч колись пропустила табуляцію
     * всередині назви, зайвий символ зіпсував би саму назву, а не зʼїв сусідню
     * колонку. Дописати `ref` після назви означало б протилежне.
     *
     * ЧОМУ ПʼЯТЬ КОЛОНОК ЩЕ ЧИТАЮТЬСЯ. Старий асет без `ref` розбирається й
     * далі — але його рядки їдуть у ПОРЯДКОВИЙ коридор ([KIND_ORDINAL]) і НЕ
     * отримують [REF_TAG]. Тобто «цей рядок не має особи» стає видимим і в
     * номері, і в тегах, замість того щоб виглядати як звичайне місце.
     * Кількість колонок — єдиний розрізняч, і він надійний: піч чистить
     * табуляції в назві ([clean] у `bake_country_places.py`).
     *
     * Never throws on a malformed row: a line that does not parse is skipped.
     */
    internal fun parseLine(line: String, idx: Long): PoiGazetteerEntity? {
        val parts = line.split('\t', limit = 6)
        if (parts.size < 5) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val cat = parts[2]
        val sub = parts[3].ifEmpty { null }
        val ref = if (parts.size >= 6) parts[4].ifEmpty { null } else null
        val name = if (parts.size >= 6) parts[5] else parts[4]
        if (cat.isEmpty() || name.isBlank()) return null
        // Один незламний звʼязок: рядок несе [REF_TAG] ТОДІ І ЛИШЕ ТОДІ, коли
        // його id походить від цього посилання. Посилання, яке не розібралось,
        // не їде в теги — інакше зшивач дублікатів звіряв би місця за ключем,
        // якого номер не підтверджує. Пара тримається однією змінною саме щоб
        // ці двоє не могли розійтись при наступній правці.
        val identity = ref?.let { r -> idForRef(r)?.let { r to it } }
        return PoiGazetteerEntity(
            id = identity?.second ?: (ID_BASE + KIND_ORDINAL * KIND_SPAN + idx),
            lat = lat,
            lon = lon,
            name = name,
            category = cat,
            subcategory = sub,
            keywords = keywordsFor(cat, sub),
            tags = identity?.let { mapOf(REF_TAG to it.first) } ?: emptyMap(),
        )
    }

    /**
     * Слова, за якими цей рядок можна знайти — і чому це ДВА виклики словника.
     *
     * Той самий [PoiVocabulary], що й для живих відповідей Overpass: без нього
     * рядок «Джерело» знаходився б лише за словом «джерело», а людина друкує
     * «вода». Тегів у потоці немає за побудовою (вони і є те, що робить
     * JSON-пак у чотири рази важчим), тож пара ключ/значення подається
     * синтетично — рівно те, що рядок і так стверджує.
     *
     * Другий виклик — не надмірність, а брід. У броду категорія і є видом
     * обʼєкта (`cat = "ford"`), а підвид несе лише РІЗНОВИД розмітки
     * (`yes`, `seasonal`, `stepping_stones`). Словник знає слово «брід» за
     * ключем `ford`, а не за значенням `yes`, тож без цього виклику 25 964
     * позначені переправи країни знаходились би тільки за буквальним
     * написанням своєї підказки — тобто рівно та тиша, з якої почалась хвиля.
     */
    internal fun keywordsFor(cat: String, sub: String?): String {
        val byValue = PoiVocabulary.keywords(
            tags = if (sub != null) mapOf(cat to sub) else emptyMap(),
            category = cat,
            subcategory = sub,
        )
        val byKey = PoiVocabulary.keywords(tags = emptyMap(), category = cat, subcategory = cat)
        return (byValue.split(' ') + byKey.split(' '))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
    }

    private val HEADER_GENERATED = Regex("generated=(\\d+)")
}
