package local.phantom.companion.feature.stream.ui.map.dossier

/**
 * P0 — КОГО описує досьє.
 *
 * До цього типу досьє знало лише «точку»: пару координат і випадковий
 * найближчий текст навколо неї. Саме тому «Бібліотека ім. Андрія Головка»
 * отримувала довідку «Проспект Георгія Гонгадзе — проспект у Подільському
 * районі…»: 400-метровий геопошук Вікіпедії чесно віддавав НАЙБЛИЖЧУ статтю,
 * а картка так само чесно підписувала її «ДОВІДКА». Помилка не в мережі —
 * помилка в тому, що ніхто не тримав у руках сутність, з якою цю статтю
 * можна було звірити.
 *
 * [PoiEntityRef] — і є та сутність: назва + категорія + ідентифікатори, які
 * дає сама мапа (OSM id, теги `wikipedia` / `wikidata`). Усе решта — здогад,
 * і поводитись із ним треба як зі здогадом.
 */
internal data class PoiEntityRef(
    /** Назва місця, як її знає мапа (null = тап у порожнє місце). */
    val name: String?,
    val category: DossierCategory,
    /** OSM id зіставленого елемента, коли Overpass відповів. */
    val osmId: Long? = null,
    /** Тег `wikipedia` — авторитетна назва статті ПРО ЦЕ місце. */
    val wikipedia: WikiTitleRef? = null,
    /** Тег `wikidata` (Q…) — авторитетний ідентифікатор сутності. */
    val wikidataId: String? = null,
    /** Сама сутність — вулиця/площа/район (тоді стаття про вулицю доречна). */
    val isThoroughfare: Boolean = false,
) {
    /** Чи є взагалі за що зачепити статтю на рівні сутності. */
    val hasAuthoritativeId: Boolean get() = wikipedia != null || wikidataId != null
}

/** Розібраний тег `wikipedia`: мовний розділ + назва статті. */
internal data class WikiTitleRef(val lang: String, val title: String) {
    val pageUrl: String
        get() = "https://$lang.wikipedia.org/wiki/" +
            java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
}

/**
 * Сутність за тегами зіставленого OSM-елемента + хінтами тапу.
 *
 * [nameHint] потрібен саме як РЕЗЕРВ: базовий стиль мапи часто віддає імʼя
 * символу, якого нема в Overpass-відповіді (інша зумована генералізація), і
 * без нього миттєва картка лишалася б безіменною.
 */
internal fun entityRefOf(
    tags: Map<String, String>,
    nameHint: String? = null,
    categoryHint: String? = null,
    subcategoryHint: String? = null,
    osmId: Long? = null,
): PoiEntityRef = PoiEntityRef(
    name = (tags["name:uk"] ?: tags["name"] ?: nameHint)?.trim()?.takeIf { it.isNotEmpty() },
    category = dossierCategoryOf(tags, categoryHint, subcategoryHint),
    osmId = osmId,
    wikipedia = parseWikipediaTag(tags),
    wikidataId = tags["wikidata"]?.trim()?.takeIf { WIKIDATA_ID.matches(it) },
    isThoroughfare = isThoroughfareTags(tags),
)

/**
 * Тег `wikipedia` у трьох формах, які реально трапляються в OSM:
 *   `uk:Хрещатик` · `wikipedia:uk=Хрещатик` · повний URL статті.
 * Без мовного префікса вважаємо українську — це українська мапа й
 * український розділ, з якого досьє й читає.
 */
internal fun parseWikipediaTag(tags: Map<String, String>): WikiTitleRef? {
    val direct = tags["wikipedia"]?.trim()?.takeIf { it.isNotEmpty() }
    if (direct != null) {
        parseWikipediaValue(direct)?.let { return it }
    }
    // `wikipedia:uk=…` — окремий ключ на кожен мовний розділ.
    for (lang in PREFERRED_WIKI_LANGS) {
        val v = tags["wikipedia:$lang"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        return WikiTitleRef(lang, decodeWikiTitle(v))
    }
    return null
}

private fun parseWikipediaValue(value: String): WikiTitleRef? {
    URL_FORM.find(value)?.let { m ->
        return WikiTitleRef(m.groupValues[1], decodeWikiTitle(m.groupValues[2]))
    }
    LANG_FORM.find(value)?.let { m ->
        return WikiTitleRef(m.groupValues[1], decodeWikiTitle(m.groupValues[2]))
    }
    return WikiTitleRef(PREFERRED_WIKI_LANGS.first(), decodeWikiTitle(value))
}

private fun decodeWikiTitle(raw: String): String =
    runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }
        .getOrDefault(raw)
        .replace('_', ' ')
        .trim()

/**
 * Чи є САМ обʼєкт вулицею / площею / районом. Якщо так — стаття про
 * проспект і є статтею про нього, і демоція недоречна.
 */
internal fun isThoroughfareTags(tags: Map<String, String>): Boolean {
    tags["highway"]?.let { if (it in THOROUGHFARE_HIGHWAYS) return true }
    tags["place"]?.let { if (it in THOROUGHFARE_PLACES) return true }
    if (tags["boundary"] == "administrative") return true
    return false
}

private val THOROUGHFARE_HIGHWAYS = setOf(
    "residential", "living_street", "primary", "secondary", "tertiary", "trunk", "motorway",
    "unclassified", "pedestrian", "footway", "path", "service", "road", "track",
)

private val THOROUGHFARE_PLACES = setOf(
    "square", "district", "suburb", "neighbourhood", "quarter", "borough",
    "city", "town", "village", "hamlet", "municipality", "region", "state",
)

/** Мовні розділи, з яких досьє готове читати, у порядку переваги. */
internal val PREFERRED_WIKI_LANGS = listOf("uk", "en")

private val WIKIDATA_ID = Regex("^Q[0-9]+$")
private val URL_FORM = Regex("""https?://([a-z\-]{2,12})\.wikipedia\.org/wiki/(.+)""")
private val LANG_FORM = Regex("""^([a-z\-]{2,12}):(.+)$""")
