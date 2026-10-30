package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.net.transit.TransitousStopClient
import local.phantom.companion.core.sensor.nav.RailLine
import local.phantom.companion.core.sensor.nav.TransitCodec
import local.phantom.companion.core.sensor.nav.TransitStop
import local.phantom.companion.feature.stream.R

/**
 * ТРАНСПОРТ ІЗ ПАКЕТА НА СКЛІ — зупинки, станції й колії, які пристрій уже
 * має офлайн.
 *
 * ЧОМУ ЦЕ ОКРЕМЕ ВІД [MapTransitLayers]. Там — стрічка ОДНОГО обраного
 * маршруту: посадка, пересадка, вихід. Тут — сама мережа: те, що просто є
 * навколо людини. Дві різні правди про транспорт, і змішувати їх в одному
 * шарі означало б, що вимкнути одну неможливо, не вимкнувши другу.
 *
 * ДВА ДЖЕРЕЛА, ОДИН ШАР. Спершу дорожній пакет — офлайн, під РЕБ, без
 * жодного запиту. Де пакет рамку не накриває — відкритий всесвітній
 * розклад (Transitous). Пакетів транспорту у світі 20.09 рівно один
 * (Люксембург), тож без другого джерела шар був порожній за побудовою
 * скрізь: і у Львові, де даних 769 зупинок, і в Києві, де 527.
 *
 * ЩО ТУТ НІКОЛИ НЕ ЗʼЯВИТЬСЯ. Ані хвилини, ані ціни. OSM не несе ні
 * розкладів, ні тарифів, а ця сама поверхня колись друкувала «Проїзд:
 * 8.00 ₴» літералом на кожній зупинці країни. Показуємо рівно те, що є в
 * даних: що це, як зветься, які номери тут зупиняються.
 */
internal class MapPackTransit(
    private val scope: CoroutineScope,
    /** Читач постійної мережі. `null` — коли хост його не подав. */
    private val fetch: (suspend (Double, Double, Double, Double) -> PackTransitSlice?)? = null,
    /**
     * Зупинки у видимій рамці з відкритого всесвітнього розкладу (Transitous).
     *
     * `null` у результаті означає «запит не вдався» — і тоді шар лишається
     * ПОРОЖНІМ, а не тримає попередню рамку. Порожньо і чесно, бо крапка
     * не на своєму місці у чужому місті гірша за відсутність крапки.
     */
    private val liveStops:
        (suspend (Double, Double, Double, Double) -> List<TransitousStopClient.MapStop>?)? = null,
    /**
     * Куди йде читання. Параметр існує заради вимірності: на [Dispatchers.IO]
     * запит біжить справжнім потоком, і жоден тест не може чесно дочекатись
     * його кінця — лише вгадати паузу.
     */
    private val io: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) {
    private val _stopsGeoJson = MutableStateFlow(EMPTY_FC)
    val stopsGeoJson: StateFlow<String> = _stopsGeoJson.asStateFlow()

    private val _railsGeoJson = MutableStateFlow(EMPTY_FC)
    val railsGeoJson: StateFlow<String> = _railsGeoJson.asStateFlow()

    private var job: Job? = null
    private var lastKey: String? = null

    /** Кеш живої половини на рамку — щоб повернення назад не коштувало запиту. */
    private val liveCache = LinkedHashMap<String, String>()

    /**
     * Камера стала — питаємо пакет, а де пакет мовчить — мережу.
     *
     * Ключ прямокутника округлюємо: камера стає десятки разів за хвилину
     * панорамування, а зупинки за пів клітинки не змінюються. Без цього
     * кожен дотик пальця коштував би повного bbox-запиту по базі на
     * мільйони рядків. Той самий ключ стримує і мережевий запит — другий
     * поріг означав би два різні уявлення про те, коли камера стала.
     */
    fun onCamera(bounds: OfflineBounds, zoom: Double) {
        val stopsWanted = zoom >= STOPS_MIN_ZOOM
        val railsWanted = zoom >= RAILS_MIN_ZOOM
        if (!stopsWanted && !railsWanted) {
            lastKey = null
            _stopsGeoJson.value = EMPTY_FC
            _railsGeoJson.value = EMPTY_FC
            return
        }
        val key = cameraKey(bounds, stopsWanted, railsWanted)
        if (key == lastKey) return
        lastKey = key
        job?.cancel()
        job = scope.launch {
            val slice = fetch?.let { reader ->
                withContext(io) {
                    runCatching {
                        reader(bounds.southLat, bounds.westLon, bounds.northLat, bounds.eastLon)
                    }.getOrNull()
                }
            }

            _railsGeoJson.value =
                if (railsWanted && slice != null) buildPackRailsGeoJson(slice.rails) else EMPTY_FC

            if (!stopsWanted) {
                _stopsGeoJson.value = EMPTY_FC
                return@launch
            }

            val packStops = slice?.stops.orEmpty()
            if (packStops.isNotEmpty()) {
                // Пакет накрив рамку — мережу не чіпаємо взагалі. Не лише
                // заради трафіку: рамка, надіслана назовні там, де офлайн
                // уже відповів, — це витік координат без жодної користі.
                _stopsGeoJson.value = buildPackStopsGeoJson(packStops)
                return@launch
            }

            val reader = liveStops
            if (reader == null) {
                _stopsGeoJson.value = EMPTY_FC
                return@launch
            }
            liveCache[key]?.let {
                _stopsGeoJson.value = it
                return@launch
            }
            val live = withContext(io) {
                runCatching {
                    reader(bounds.southLat, bounds.westLon, bounds.northLat, bounds.eastLon)
                }.getOrNull()
            }
            // Відмова мережі — порожній шар, НЕ попередній кадр.
            val json = live?.let(::buildLiveStopsGeoJson) ?: EMPTY_FC
            if (live != null) {
                if (liveCache.size >= LIVE_CACHE_FRAMES) {
                    liveCache.remove(liveCache.keys.first())
                }
                liveCache[key] = json
            }
            // Камера могла поїхати далі, поки летів запит: відповідь на стару
            // рамку не має права перемалювати нову.
            if (lastKey == key) _stopsGeoJson.value = json
        }
    }

    internal companion object {
        /** Скільки рамок живих зупинок тримати в памʼяті. */
        const val LIVE_CACHE_FRAMES = 12

        /**
         * Нижче цього зуму зупинки перестають бути мітками і стають шумом:
         * у Києві їх 7 335, у країні 66 106. Це не оптимізація — на z11
         * країна перетворилась би на суцільну кашу з крапок, під якою не
         * видно дороги.
         */
        const val STOPS_MIN_ZOOM = 13.0

        /** Колія читається й здалеку, а ліній на порядок менше за зупинки. */
        const val RAILS_MIN_ZOOM = 9.0

        /** ~0.01° ≈ 1,1 км — під межею, за якою людина помічає підвантаження. */
        const val KEY_STEP = 0.01

        fun cameraKey(b: OfflineBounds, stops: Boolean, rails: Boolean): String {
            fun q(v: Double) = Math.round(v / KEY_STEP)
            return "${q(b.southLat)}/${q(b.westLon)}/${q(b.northLat)}/${q(b.eastLon)}/$stops$rails"
        }
    }
}

/**
 * Що пакет віддав для цього прямокутника.
 *
 * Публічний, на відміну від [MapPackTransit]: цей тип перетинає межу модуля —
 * його будує `:app` (AppContainer.fetchPackTransit), бо саме там живе
 * єдиний примірник сховища. Внутрішній тип тут дав би шов, який неможливо
 * підключити.
 */
data class PackTransitSlice(
    val stops: List<TransitStop>,
    val rails: List<RailLine>,
)

/**
 * Чим є ця зупинка — за тегами, які пекар справді поклав.
 *
 * Порядок перевірок — від вужчого до ширшого: вузол метро часто несе ще й
 * `public_transport=station`, тож питати загальне першим означало б
 * втратити конкретне.
 */
internal fun packStopMode(tags: Map<String, String>): TransitMode = when {
    tags["station"] == "subway" || tags["subway"] == "yes" -> TransitMode.METRO
    tags["railway"] == "tram_stop" || tags["tram"] == "yes" -> TransitMode.TRAM
    tags["railway"] == "station" || tags["railway"] == "halt" ||
        tags["train"] == "yes" -> TransitMode.RAIL
    tags["trolleybus"] == "yes" -> TransitMode.TROLLEYBUS
    tags["highway"] == "bus_stop" || tags["bus"] == "yes" ||
        tags["share_taxi"] == "yes" -> TransitMode.BUS
    tags["funicular"] == "yes" -> TransitMode.FUNICULAR
    else -> TransitMode.OTHER
}

/**
 * Підпис зупинки: назва, і під нею номери, якщо вони є.
 *
 * Номерів беремо небагато — на склі довгий хвіст нечитний, а «і ще» чесніше
 * за обрізаний рядок, що вдає повний перелік.
 */
private val GLUED_COMMA = Regex(",(?=\\S)")

internal fun packStopLabel(stop: TransitStop, maxRefs: Int = 4): String {
    val refs = TransitCodec.decodeRoutes(stop.routes).map { it.second }.distinct()
    // Чеські стрічки пишуть «Poruba,Otakara Jeremiáše» без пробілу після коми —
    // на склі це читалось як одне склеєне слово.
    val name = stop.name.orEmpty().replace(GLUED_COMMA, ", ")
    if (refs.isEmpty()) return name
    val shown = refs.take(maxRefs).joinToString(", ")
    val tail = if (refs.size > maxRefs) " +${refs.size - maxRefs}" else ""
    return if (name.isEmpty()) shown + tail else "$name\n$shown$tail"
}

/**
 * Що написати на картці зупинки під її назвою.
 *
 * Порожній рядок означає «даних про номери немає» — і тоді картка мовчить.
 * Це не те саме, що «сюди нічого не їздить»: 18 417 зупинок країни з 66 106
 * мають номери, решта просто не внесені в маршрутні відношення OSM. Написати
 * тут «маршрутів немає» було б твердженням, якого дані не роблять.
 */
internal fun packTransitDetail(routes: String): String? {
    val byMode = LinkedHashMap<String, MutableList<String>>()
    for ((mode, ref) in TransitCodec.decodeRoutes(routes)) {
        byMode.getOrPut(mode) { mutableListOf() }.add(ref)
    }
    if (byMode.isEmpty()) return null
    return byMode.entries.joinToString(" · ") { (mode, refs) ->
        "${packModeWord(mode)} ${refs.joinToString(", ")}"
    }
}

/**
 * Що людина застане на місці — і НІЧОГО про те, коли приїде.
 *
 * Ці теги пекар возить (`shelter`, `covered`, `wheelchair`, `tactile_paving`
 * — 16 907 / 1 228 / 607 / 1 301 зупинки країни), і якщо їх не показати,
 * вони стануть даними, які ніхто не читає, — тим самим класом, що й ключі
 * дороги, які три тижні не доїжджали до пакета.
 *
 * Мовчання тут означає «дані про це не внесені», а не «навісу немає». Тому
 * пишемо лише те, що тег стверджує ствердно.
 */
internal fun packStopComfort(tags: Map<String, String>): String? {
    val says = ArrayList<String>(3)
    if (tags["shelter"] == "yes" || tags["covered"] == "yes") says += MapText.s(R.string.mapl_e_comfort_shelter)
    if (tags["wheelchair"] == "yes") says += MapText.s(R.string.mapl_e_comfort_wheelchair)
    if (tags["tactile_paving"] == "yes") says += MapText.s(R.string.mapl_e_comfort_tactile)
    return says.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/**
 * Рід зупинки словом — з ознаки `mode` фічі, тобто з імені [TransitMode].
 *
 * Не через [packModeWord]: той читає словник OSM («subway», «train»), і
 * `TransitMode.OTHER` пройшов би крізь нього як підпис «other» — англійське
 * слово в українській картці, яке ще й стверджує рід, якого ніхто не назвав.
 * Невідоме — `null`, і картка про рід просто мовчить.
 */
internal fun packStopModeWord(modeName: String): String? = when (modeName) {
    TransitMode.METRO.name -> MapText.s(R.string.mapl_e_mode_metro)
    TransitMode.TRAM.name -> MapText.s(R.string.mapl_e_mode_tram)
    TransitMode.TROLLEYBUS.name -> MapText.s(R.string.mapl_e_mode_trolleybus)
    TransitMode.BUS.name -> MapText.s(R.string.mapl_e_mode_bus)
    TransitMode.RAIL.name -> MapText.s(R.string.mapl_e_mode_rail)
    TransitMode.FUNICULAR.name -> MapText.s(R.string.mapl_e_mode_funicular)
    TransitMode.FERRY.name -> MapText.s(R.string.mapl_e_mode_ferry)
    else -> null
}

internal fun packModeWord(mode: String): String = when (mode) {
    "subway" -> MapText.s(R.string.mapl_e_mode_metro)
    "train" -> MapText.s(R.string.mapl_e_mode_rail)
    "light_rail" -> MapText.s(R.string.mapl_e_mode_light_rail)
    "tram" -> MapText.s(R.string.mapl_e_mode_tram)
    "trolleybus" -> MapText.s(R.string.mapl_e_mode_trolleybus)
    "bus" -> MapText.s(R.string.mapl_e_mode_bus)
    "share_taxi" -> MapText.s(R.string.mapl_e_mode_share_taxi)
    "monorail" -> MapText.s(R.string.mapl_e_mode_monorail)
    "funicular" -> MapText.s(R.string.mapl_e_mode_funicular)
    else -> mode
}

/** Колія магістральна чи підʼїзна — єдина різниця, що варта пікселя. */
internal fun packRailIsMain(tags: Map<String, String>): Boolean =
    tags["usage"] in setOf("main", "branch") && tags["service"] == null

internal fun buildPackStopsGeoJson(stops: List<TransitStop>): String {
    if (stops.isEmpty()) return EMPTY_FC
    val features = ArrayList<String>(stops.size)
    for (s in stops) {
        val mode = packStopMode(s.tags)
        features += "{\"type\":\"Feature\",\"properties\":{" +
            "\"colour\":\"${transitLegHex(-1, mode)}\"," +
            "\"mode\":\"${mode.name}\"," +
            "\"id\":\"${s.id}\"," +
            "\"label\":\"${escapeJsonString(packStopLabel(s))}\"," +
            "\"name\":\"${escapeJsonString(s.name.orEmpty())}\"," +
            "\"routes\":\"${escapeJsonString(s.routes)}\"," +
            "\"comfort\":\"${escapeJsonString(packStopComfort(s.tags).orEmpty())}\"}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${s.lon},${s.lat}]}}"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[${features.joinToString(",")}]}"
}

/**
 * Рід транспорту зупинки Transitous — за словником MOTIS.
 *
 * Словник сервера ширший за наш і росте без нашої згоди. Невідоме слово
 * падає в [TransitMode.OTHER], а не в BUS: синьою крапкою «автобус» на
 * поромному причалі шар стверджував би те, чого дані не кажуть.
 */
internal fun liveStopMode(modes: List<String>): TransitMode {
    val up = modes.map { it.uppercase() }.toSet()
    // Від вужчого до ширшого: зупинка часто несе кілька родів одразу, і
    // трамвай, зведений до автобуса, — це саме те, по чому людина шукає.
    return when {
        "METRO" in up || "SUBWAY" in up -> TransitMode.METRO
        "TRAM" in up -> TransitMode.TRAM
        "TROLLEYBUS" in up -> TransitMode.TROLLEYBUS
        "FUNICULAR" in up -> TransitMode.FUNICULAR
        "FERRY" in up -> TransitMode.FERRY
        up.any { it == "RAIL" || it.endsWith("_RAIL") || it == "LONG_DISTANCE" } -> TransitMode.RAIL
        "BUS" in up || "COACH" in up -> TransitMode.BUS
        else -> TransitMode.OTHER
    }
}

/**
 * Жива половина шару — рівно ті самі ознаки, що й пакетна.
 *
 * Однакові імена полів не випадковість: шар і обробник тапу читають ОДИН
 * набір ознак, тож жива зупинка малюється й натискається тим самим кодом,
 * що й пакетна, без другої гілки, яку легко забути полагодити.
 *
 * `routes` тут порожній навмисно: дошка MOTIS знає номери лише для
 * конкретної зупинки, а рамка їх не несе. Порожньо — значить картка про
 * номери мовчить, доки її не відкрили; вигадати їх тут було б брехнею.
 */
internal fun buildLiveStopsGeoJson(stops: List<TransitousStopClient.MapStop>): String {
    if (stops.isEmpty()) return EMPTY_FC
    val features = ArrayList<String>(stops.size)
    for (s in stops) {
        val mode = liveStopMode(s.modes)
        features += "{\"type\":\"Feature\",\"properties\":{" +
            "\"colour\":\"${transitLegHex(-1, mode)}\"," +
            "\"mode\":\"${mode.name}\"," +
            "\"id\":\"${escapeJsonString(s.id)}\"," +
            "\"label\":\"${escapeJsonString(s.name)}\"," +
            "\"name\":\"${escapeJsonString(s.name)}\"," +
            "\"routes\":\"\"," +
            "\"comfort\":\"\"," +
            "\"live\":\"true\"}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${s.lon},${s.lat}]}}"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[${features.joinToString(",")}]}"
}

internal fun buildPackRailsGeoJson(rails: List<RailLine>): String {
    if (rails.isEmpty()) return EMPTY_FC
    val features = ArrayList<String>(rails.size)
    for (r in rails) {
        if (r.points.size < 2) continue
        val coords = r.points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
        features += "{\"type\":\"Feature\",\"properties\":{" +
            "\"main\":${packRailIsMain(r.tags)}," +
            "\"name\":\"${escapeJsonString(r.name.orEmpty())}\"}," +
            "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}"
    }
    if (features.isEmpty()) return EMPTY_FC
    return "{\"type\":\"FeatureCollection\",\"features\":[${features.joinToString(",")}]}"
}
