package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiVocabulary
import local.phantom.companion.core.net.map.OpeningHours
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapText
import local.phantom.companion.feature.stream.ui.map.source.BatchDSource

/**
 * NAV-4 / PRECISION — «досьє місця»: те, що збирає один тап.
 *
 * Кожне поле навмисно nullable/порожнє — відсутнє джерело означає відсутню
 * секцію (Закон 1), ніколи заглушку. Новим у редизайні є [pending] і
 * [failed]: досьє більше не приходить одним куском через 15 секунд, воно
 * приходить СНІМКАМИ. Перший знімок будується з того, що вже в руках у
 * момент тапу (назва, категорія, координата) і не чекає жодного байта з
 * мережі; далі кожен слот доливається окремо. Тому картка мусить уміти
 * сказати не лише «даних нема», а й «дані ще їдуть» — це різні стани, і
 * малюються вони по-різному (скелетон проти чесного прочерку).
 */
internal data class PlaceDossier(
    val target: DossierTarget,
    val title: String,
    /** Український рядок виду місця («кафе», «зупинка автобуса»)… або null. */
    val kindLabel: String?,
    /** P0/P3 — категорія: локалізована назва + акцент усієї картки. */
    val category: DossierCategory,
    /** P0 — кого саме описує ця картка. */
    val entity: PoiEntityRef,
    val address: String?,
    /** Повні OSM-теги найкращого збігу (порожньо = нічого не зіставилось). */
    val tags: Map<String, String>,
    val openStatus: OpeningHours.Status?,
    val rawHours: String?,
    val building: PlaceDossierClient.BuildingCard?,
    /** P0 — довідка, ЗВІРЕНА із сутністю (див. [DossierReference]). */
    val reference: DossierReference?,
    /** NAV-6 — маршрути, що обслуговують цю зупинку (лише для зупинок). */
    val transitLines: List<PlaceDossierClient.RouteLineRef>,
    /** Те саме, злите з активом пристрою: частоти, переходи, вікно роботи. */
    val transit: DossierTransit? = null,
    /** Заклади всередині контейнера (ТРЦ, ринок, вокзал) — з активу. */
    val inside: List<InsidePlace> = emptyList(),
    /** Назва контейнера, у якому лежить ЦЕЙ заклад («у "Retroville"»). */
    val containerName: String? = null,
    /**
     * Куди насправді вести маршрут: найближчий вхід у будівлю замість
     * центроїда. null — цілі входу нема, ведемо в точку як раніше.
     */
    val routePoint: Pair<Double, Double>? = null,
    /** «схоже поруч» — та сама категорія, найближчі перші, без себе. */
    val similar: List<PoiEntity>,
    /** Заголовок для [similar] — «СХОЖЕ ПОРУЧ» або «ПОРУЧ ЗІ СТАНЦІЄЮ». */
    val similarTitle: String = MapText.s(R.string.mapl_d_similar_title_default),
    /** Слоти, які ще їдуть. Порожньо = досьє зібране повністю. */
    val pending: Set<DossierSlot> = emptySet(),
    /** Слоти, які здалися (таймаут/мережа). Малюються як чесна невдача. */
    val failed: Set<DossierSlot> = emptySet(),
    /** Слоти з локальною відповіддю на руках, поки мережа добирає глибше. */
    val deepening: Set<DossierSlot> = emptySet(),
    /** Звідки прийшла особистість місця — газетир, сітка доріг чи мережа. */
    val identitySource: DossierSourceKind? = null,
) {
    val phone: String? get() = tags["phone"] ?: tags["contact:phone"]
    val website: String? get() = tags["website"] ?: tags["contact:website"]
    val cuisine: String? get() = tags["cuisine"]?.replace(';', ',')?.replace('_', ' ')
    val wheelchair: String? get() = tags["wheelchair"]

    /** Бренд або оператор — банкомат «ПриватБанку», поштомат «Нової Пошти». */
    val brand: String? get() = tags["brand"] ?: tags["operator"]

    /** Місце, куди ходять (тобто години роботи мають сенс) — крамниця, кафе, офіс… */
    val isVenue: Boolean get() = tags.keys.any {
        it == "amenity" || it == "shop" || it == "tourism" || it == "office" || it == "leisure"
    }

    /** Поверх усередині будівлі/ТЦ («2 поверх»), коли протеговано. */
    val level: String? get() = tags["level"]?.takeIf { it.isNotBlank() }

    /**
     * Місце-контейнер: питання до нього — «що всередині», а не «де ще такий».
     * Ринок і вокзал теж контейнери, хоч і не мол.
     */
    val isContainer: Boolean get() = tags["shop"] == "mall" ||
        tags["amenity"] in setOf("marketplace", "bus_station") ||
        tags["building"] in setOf("retail", "mall", "commercial")

    /** Досьє зібране до кінця — тільки таке варто класти в кеш. */
    val isComplete: Boolean get() = pending.isEmpty() && deepening.isEmpty()

    fun isPending(slot: DossierSlot): Boolean = slot in pending
    fun hasFailed(slot: DossierSlot): Boolean = slot in failed
    fun isDeepening(slot: DossierSlot): Boolean = slot in deepening

    /**
     * Короткі факти-пігулки в один погляд — те, що вирішує «зайти чи ні» ще
     * до того, як прочитано адресу. Кожна пігулка існує лише якщо існує її
     * тег (Закон 1); порожній список не малює нічого.
     */
    fun quickChips(): List<String> = buildList {
        if (tags["generator:source"] != null || tags["power"] == "generator" || tags["backup_generator"] == "yes") {
            val src = tags["generator:source"] ?: MapText.s(R.string.mapl_d_gen_source_autonomous)
            add(MapText.s(R.string.mapl_d_chip_generator, src))
        }
        if (tags["starlink"] == "yes" || tags["internet_access:type"] == "satellite") {
            add(MapText.s(R.string.mapl_d_chip_satellite_internet))
        }
        if (ShelterTruth.isProtective(tags)) {
            add(
                if (ShelterTruth.isBombShelter(tags)) {
                    MapText.s(R.string.mapl_d_chip_bomb_shelter)
                } else {
                    MapText.s(R.string.mapl_d_chip_shelter)
                },
            )
        }
        if (tags["barrier"] == "lift_gate" || tags["barrier"] == "gate" || tags["barrier"] == "toll_booth") {
            val b = when (tags["barrier"]) {
                "lift_gate" -> MapText.s(R.string.mapl_d_chip_lift_gate)
                "gate" -> MapText.s(R.string.mapl_d_chip_gate)
                "toll_booth" -> MapText.s(R.string.mapl_d_chip_toll_booth)
                else -> MapText.s(R.string.mapl_d_chip_barrier)
            }
            add(b)
        }
        if (tags["access"] == "private" || tags["access"] == "no") add(MapText.s(R.string.mapl_d_chip_private_area))
        if (tags["amenity"] == "charging_station") {
            val pwr = tags["charging_station:output"] ?: tags["socket:output"]
            if (pwr != null) {
                add(MapText.s(R.string.mapl_d_chip_ev_charging_power, pwr))
            } else {
                add(MapText.s(R.string.mapl_d_chip_ev_charging))
            }
        }
        if (tags["man_made"] == "water_well" || tags["amenity"] == "drinking_water" || tags["natural"] == "spring") {
            val w = when {
                tags["natural"] == "spring" -> MapText.s(R.string.mapl_d_chip_spring)
                tags["man_made"] == "water_well" -> MapText.s(R.string.mapl_d_chip_water_well)
                else -> MapText.s(R.string.mapl_d_chip_drinking_water)
            }
            add(w)
        }
        if (tags["emergency"] == "defibrillator") add(MapText.s(R.string.mapl_d_chip_defibrillator))
        if (tags["parking"] == "underground" || tags["building"] == "parking" && tags["parking:type"] == "underground") {
            add(MapText.s(R.string.mapl_d_chip_underground_parking))
        }
        if (tags["amenity"] == "parcel_locker") add(MapText.s(R.string.mapl_d_chip_parcel_locker))
        if (tags["surveillance"] != null || tags["surveillance:type"] != null) add(MapText.s(R.string.mapl_d_chip_surveillance))

        if (tags["amenity"] == "fuel") {
            val fList = buildList {
                if (tags["fuel:octane_95"] == "yes") add(MapText.s(R.string.mapl_d_fuel_short_95))
                if (tags["fuel:diesel"] == "yes") add(MapText.s(R.string.mapl_d_fuel_short_diesel))
                if (tags["fuel:lpg"] == "yes") add(MapText.s(R.string.mapl_d_fuel_short_lpg))
            }
            if (fList.isNotEmpty()) add(MapText.s(R.string.mapl_d_chip_fuel_list, fList.joinToString("/")))
            if (tags["compressed_air"] == "yes") add(MapText.s(R.string.mapl_d_chip_compressed_air))
            if (tags["car_wash"] == "yes" || tags["amenity"] == "car_wash") add(MapText.s(R.string.mapl_d_chip_car_wash))
        }
        if (tags["shop"] == "tyres" || tags["service:vehicle:tyres"] == "yes") add(MapText.s(R.string.mapl_d_chip_tyres))
        if (tags["amenity"] == "car_repair") add(MapText.s(R.string.mapl_d_chip_car_repair))

        if (tags["amenity"] == "atm" || tags["atm"] == "yes") add(MapText.s(R.string.mapl_d_chip_atm))
        tags["cash_in"]?.let { if (it == "yes") add(MapText.s(R.string.mapl_d_chip_cash_in)) }
        tags["currency:UAH"]?.let { if (it == "yes") add(MapText.s(R.string.mapl_d_chip_uah)) }
        if (tags["currency:USD"] == "yes" || tags["currency:EUR"] == "yes") add(MapText.s(R.string.mapl_d_chip_foreign_currency))
        val cards = tags["payment:cards"] ?: tags["payment:visa"] ?: tags["payment:mastercard"]
        if (cards == "yes") add(MapText.s(R.string.mapl_d_chip_card))
        if (tags["payment:cash"] == "yes") add(MapText.s(R.string.mapl_d_chip_cash))

        when (tags["internet_access"]) { "wlan", "yes" -> add("Wi-Fi"); else -> {} }
        if (tags["outdoor_seating"] == "yes") add(MapText.s(R.string.mapl_d_chip_terrace))
        if (tags["takeaway"] == "yes" || tags["takeaway"] == "only") add(MapText.s(R.string.mapl_d_chip_takeaway))
        if (tags["drive_through"] == "yes") add(MapText.s(R.string.mapl_d_chip_drive_through))
        if (tags["self_service"] == "yes") add(MapText.s(R.string.mapl_d_chip_self_service))
        if (tags["delivery"] == "yes") add(MapText.s(R.string.mapl_d_chip_delivery))
        if (rawHours == "24/7") add("24/7")
    }

    /**
     * Додаткові факти поза базовим набором — за категорією, чесно за тегами.
     *
     * Закон закритого словника: значення показується лише тоді, коли
     * [PoiVocabulary.tagValueLabel] його знає. Не знає — рядка нема, а не
     * сире `permissive`/`basement`/`lift_gate` (вирок власника 15.09).
     * `barrier`/`access`, уже сказані пігулкою в [quickChips], тут не
     * повторюються — лише решта значень цих тегів.
     */
    fun extraFacts(): List<Pair<String, String>> = buildList {
        brand?.let { add(MapText.s(R.string.mapl_d_fact_network) to it) }
        tags["operator"]?.takeIf { it != brand }?.let { add(MapText.s(R.string.mapl_d_fact_operator) to it) }
        tags["capacity"]?.let { add(MapText.s(R.string.mapl_d_fact_capacity) to MapText.s(R.string.mapl_d_fact_capacity_persons, it)) }
        tags["generator:output"]?.let { formatPower(it)?.let { p -> add(MapText.s(R.string.mapl_d_fact_generator_output) to p) } }
        tags["socket:type2"]?.let { if (it == "yes") add(MapText.s(R.string.mapl_d_fact_charging) to "Type 2 Mennekes") }
        tags["socket:type2_combo"]?.let { if (it == "yes") add(MapText.s(R.string.mapl_d_fact_charging) to "CCS 2 Combo") }
        tags["socket:ccs"]?.let { if (it == "yes") add(MapText.s(R.string.mapl_d_fact_charging) to "CCS Combo") }
        tags["socket:chademo"]?.let { if (it == "yes") add(MapText.s(R.string.mapl_d_fact_charging) to "CHAdeMO") }
        tags["charging_station:output"]?.let { formatPower(it)?.let { p -> add(MapText.s(R.string.mapl_d_fact_charging_output) to p) } }

        (tags["shelter_type"] ?: tags["bunker_type"])?.let { kind ->
            val key = if (tags["shelter_type"] != null) "shelter_type" else "bunker_type"
            PoiVocabulary.tagValueLabel(key, kind)?.let { word ->
                val label = if (ShelterTruth.isProtective(tags)) {
                    MapText.s(R.string.mapl_d_fact_shelter_type)
                } else {
                    MapText.s(R.string.mapl_d_fact_canopy_type)
                }
                add(label to word)
            }
        }
        tags["barrier"]?.takeIf { it !in CHIP_BARRIERS }?.let { b ->
            PoiVocabulary.tagValueLabel("barrier", b)?.let { add(MapText.s(R.string.mapl_d_fact_barrier_type) to it) }
        }
        tags["access"]?.takeIf { it != "private" && it != "no" && it != "yes" }?.let { a ->
            PoiVocabulary.tagValueLabel("access", a)?.let { add(MapText.s(R.string.mapl_d_fact_access_mode) to it) }
        }
        if (tags["fee"] == "yes") {
            tags["charge"]?.let { add(MapText.s(R.string.mapl_d_fact_cost) to formatCharge(it)) }
        }

        tags["email"]?.let { add(MapText.s(R.string.mapl_d_fact_email) to it) }
        if (brand == null) tags["network"]?.let { add(MapText.s(R.string.mapl_d_fact_network) to it) }
        tags["operator:phone"]?.let { add(MapText.s(R.string.mapl_d_fact_operator_phone) to it) }
        tags["surface"]?.let { PoiVocabulary.tagValueLabel("surface", it)?.let { s -> add(MapText.s(R.string.mapl_d_fact_surface) to s) } }
        tags["smoothness"]?.let {
            PoiVocabulary.tagValueLabel("smoothness", it)?.let { s -> add(MapText.s(R.string.mapl_d_fact_smoothness) to s) }
        }
        tags["maxspeed"]?.let { add(MapText.s(R.string.mapl_d_fact_maxspeed) to MapText.s(R.string.mapl_d_fact_speed_kmh, it)) }
        tags["architect"]?.let { add(MapText.s(R.string.mapl_d_fact_architect) to it) }
        tags["start_date"]?.let { add(MapText.s(R.string.mapl_d_fact_start_date) to it) }
        tags["heritage"]?.let { PoiVocabulary.tagValueLabel("heritage", it)?.let { h -> add(MapText.s(R.string.mapl_d_fact_heritage) to h) } }
        tags["ele"]?.let { add(MapText.s(R.string.mapl_d_fact_elevation) to MapText.s(R.string.mapl_d_fact_meters, it)) }
        tags["wikipedia"]?.let { add("Wikipedia" to it) }
    }

    /** Стислий дайджест фактів для запиту до PHANTOM + паритет із `place.info`. */
    fun digest(): String = buildString {
        append(title)
        kindLabel?.let { append(" ($it)") }
        address?.let { append(" — ").append(it) }
        rawHours?.let { append(MapText.s(R.string.mapl_d_digest_hours)).append(it) }
        building?.let { b ->
            b.levels?.let { append(MapText.s(R.string.mapl_d_digest_levels)).append(it) }
            b.buildingType?.let { append(MapText.s(R.string.mapl_d_digest_building)).append(it) }
        }
        level?.let { append(MapText.s(R.string.mapl_d_digest_level)).append(it) }
        containerName?.let { append(MapText.s(R.string.mapl_d_digest_inside_container)).append(it) }
        if (inside.isNotEmpty()) append(MapText.s(R.string.mapl_d_digest_inside_count)).append(inside.size)
        val lines = transit?.lines?.map { it.ref }?.filter { it.isNotBlank() }
            ?: transitLines.map { it.ref ?: it.name ?: "?" }
        if (lines.isNotEmpty()) {
            append(MapText.s(R.string.mapl_d_digest_lines))
            append(lines.take(6).joinToString())
        }
    }
}

/** Що саме було тапнуто: точка і, за наявності, особистість POI за нею. */
internal data class DossierTarget(
    val lat: Double,
    val lon: Double,
    val nameHint: String? = null,
    /** Хінти тегів із тапнутого POI (категорія → підкатегорія). */
    val categoryHint: String? = null,
    val subcategoryHint: String? = null,
    /** Оцінка закладу Nightpost — лише коли місце прийшло з сервера. */
    val rating: local.phantom.companion.feature.stream.ui.map.around.AroundRating? = null,
    val venueId: String? = null,
)

/**
 * Слоти досьє — одиниця прогресивного завантаження. Кожен їде своїм
 * запитом, зі своїм таймаутом, і жоден не блокує ані інший, ані картку.
 */
internal enum class DossierSlot { ADDRESS, DETAIL, BUILDING, REFERENCE, TRANSIT, SIMILAR, INSIDE }

/**
 * P0 — довідка РАЗОМ із вердиктом про те, чиєю вона є.
 *
 * Саме тут вмирає баг «бібліотека з довідкою про проспект»: текст більше не
 * може потрапити в UI без ярлика [WikiVerdict], а UI не має жодного шляху
 * надрукувати «ДОВІДКА» над чимось, що не [WikiVerdict.ENTITY].
 */
internal data class DossierReference(
    val article: DossierArticle,
    val verdict: WikiVerdict,
) {
    val isAboutEntity: Boolean get() = verdict == WikiVerdict.ENTITY
}

/**
 * Чи схожий цей тап на зупинку громадського транспорту? Свідомо мʼяко:
 * тап часто лягає на іконку базової мапи, а не на OSM-вузол зупинки, тож
 * довіряємо ще й хінтам категорії/підкатегорії/назви (та їхнім українським
 * словам). Хибне спрацювання коштує одного зайвого запиту маршрутів, який
 * поверне порожнечу; хибний пропуск лишає картку станції без маршрутів —
 * рівно те «тапнув зупинку, а інфи нема», на яке скаржився оператор.
 */
internal fun looksLikeTransitStop(tags: Map<String, String>, target: DossierTarget): Boolean {
    if (tags["highway"] == "bus_stop" ||
        tags["railway"] in setOf("tram_stop", "station", "halt", "subway_entrance", "stop") ||
        tags["public_transport"] in setOf("platform", "stop_position", "station") ||
        tags["amenity"] in setOf("bus_station", "ferry_terminal")
    ) return true
    val words = listOfNotNull(target.categoryHint, target.subcategoryHint, target.nameHint)
        .joinToString(" ").lowercase()
        .split(NON_LETTER)
        .filter { it.isNotEmpty() }
    if (words.any(::saysTransit)) return true
    return words.any { it in BatchDSource.STATION_WORDS } && words.any(::qualifiesStation)
}

private fun matches(word: String, cyrillicPrefixes: List<String>, latinWords: Set<String>): Boolean =
    word in latinWords || cyrillicPrefixes.any { word.startsWith(it) }

private fun saysTransit(word: String): Boolean =
    matches(word, BatchDSource.TRANSIT_HINT_PREFIXES, TRANSIT_LATIN_WORDS)

private fun qualifiesStation(word: String): Boolean =
    matches(word, BatchDSource.STATION_QUALIFIER_PREFIXES, STATION_QUALIFIER_LATIN)

private val NON_LETTER = Regex("""[^\p{L}]+""")

private val TRANSIT_LATIN_WORDS = setOf(
    "bus", "buses", "tram", "trams", "trolleybus", "railway", "rail", "train", "trains",
    "subway", "metro", "underground", "halt", "platform", "ferry", "stop",
)

private val STATION_QUALIFIER_LATIN = setOf(
    "railway", "rail", "train", "bus", "tram", "metro", "subway", "central", "main",
)

/** Overpass-селектор для «схоже поруч», з тегів зіставленого елемента. */
internal fun similarSelector(tags: Map<String, String>): String? {
    for (key in listOf("amenity", "shop", "tourism", "leisure")) {
        val v = tags[key] ?: continue
        if (v.matches(Regex("[a-z_]+"))) return "$key=$v"
    }
    return null
}

/** Готівка / ліки / їжа / крамниця біля вокзалу — що шукає мандрівник. */
internal const val STATION_SERVICES_SELECTOR =
    "amenity~\"atm|bank|pharmacy|cafe|fast_food|toilets\""

/** Значення `barrier`, які [PlaceDossier.quickChips] уже назвав пігулкою. */
private val CHIP_BARRIERS = setOf("lift_gate", "gate", "toll_booth")

private val POWER_VALUE = Regex("""^([\d.,]+)\s?([A-Za-z]+)$""")
private val POWER_UNITS = setOf("w", "kw", "mw", "kva", "kwh", "v", "a")

/**
 * `generator:output`/`charging_station:output` — OSM пише їх як «число +
 * одиниця» (Key:generator:output), і сама одиниця мови не має: «5 kW»
 * зрозуміле й українською. Не зміряно живих значень цього тега (Overpass по
 * Остраві 15.09 повернув нуль обʼєктів із ним) — тож перевірка форми, а не
 * таблиця значень: не число+одиниця → рядок не малюється, а не друкує
 * вільний текст як є.
 */
internal fun formatPower(raw: String): String? {
    val trimmed = raw.trim()
    val m = POWER_VALUE.find(trimmed) ?: return null
    return trimmed.takeIf { m.groupValues[2].lowercase() in POWER_UNITS }
}

/**
 * `charge` — ціна лишається як є (число + код валюти читає кожен), але OSM
 * часто дописує англійське слово одиниці часу («10 CZK/hour», виміряно
 * живим Overpass по Остраві 15.09) — тільки ці слова й перекладає
 * [PoiVocabulary], щоб таблиця слів лишалась ОДНА (Закон дому 15.09).
 */
internal fun formatCharge(raw: String): String = PoiVocabulary.translateChargeUnit(raw.trim())
