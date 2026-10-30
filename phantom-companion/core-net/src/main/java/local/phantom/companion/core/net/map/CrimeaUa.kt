package local.phantom.companion.core.net.map

/**
 * Крим — Україна (ГА ООН 68/262), і назви там українські.
 *
 * Геокодери віддають окупаційну адміністрацію: Photon — «Республика Крым»,
 * «город Ялта», «Симферополь»; Nominatim з `accept-language=uk` — «Республіка
 * Крим» (переклад тієї ж окупаційної одиниці). Усе, що показується людині з
 * геокодера, проходить через [text].
 */
object CrimeaUa {

    fun contains(lat: Double, lon: Double): Boolean =
        lat in 44.35..46.23 && lon in 32.45..36.70 && !(lat > 45.95 && lon > 34.55)

    private val regions = listOf(
        Regex("(?<!Автономна )Республіка Крим") to "Автономна Республіка Крим",
        Regex("Республика Крым") to "Автономна Республіка Крим",
        Regex("(?<!Autonomous )Republic of Crimea") to "Autonomous Republic of Crimea",
        Regex("(?<!Autonomní )[Rr]epublika Krym") to "Autonomní republika Krym",
        Regex("[Гг]ород федерального значения Севастополь") to "Севастополь",
    )

    private val cities = mapOf(
        "Симферополь" to "Сімферополь",
        "Керчь" to "Керч",
        "Евпатория" to "Євпаторія",
        "Феодосия" to "Феодосія",
        "Армянск" to "Армянськ",
        "Красноперекопск" to "Красноперекопськ",
        "Белогорск" to "Білогірськ",
        "Старый Крым" to "Старий Крим",
        "Щёлкино" to "Щолкіне",
        "Инкерман" to "Інкерман",
        "Черноморское" to "Чорноморське",
        "Раздольное" to "Роздольне",
        "Первомайское" to "Первомайське",
        "Красногвардейское" to "Красногвардійське",
        "Нижнегорский" to "Нижньогірський",
        "Кировское" to "Кіровське",
        "Симеиз" to "Сімеїз",
        "Новый Свет" to "Новий Світ",
        "Приморский" to "Приморський",
        "Ленино" to "Леніне",
    )

    private val cityRx = Regex("(?<!\\p{L})(${cities.keys.joinToString("|")})(?!\\p{L})")
    private val gorod = Regex("(?<!\\p{L})[Гг]ород (?=\\p{Lu})")

    /** Регіон виправляється скрізь; міста — лише для точки на півострові, бо
     *  «Белогорск» чи «Первомайское» є і в Росії. */
    fun text(s: String, lat: Double? = null, lon: Double? = null): String {
        val out = regions.fold(s) { acc, (rx, uk) -> rx.replace(acc, uk) }
        if (lat == null || lon == null || !contains(lat, lon)) return out
        return cityRx.replace(gorod.replace(out, "")) { cities.getValue(it.value) }
    }
}
