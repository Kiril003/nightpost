package local.phantom.companion.core.geo.place

/**
 * Іменовані території України з реальними рамками.
 *
 * Джерело рамок:
 *  - країна, області, АР Крим, м. Київ — полігони OSM (`admin_level=4`), ті
 *    самі, що вже лежать у `feature-stream/assets/country/ua/oblasts.geojson`; рамка
 *    й площа пораховані з геометрії;
 *  - райони й міста — межі OSM через Nominatim, і кожна рамка перевірена на
 *    вкладеність у свою область; ті, що перевірку не пройшли, у таблицю не
 *    потрапили взагалі, а не потрапили «приблизно».
 *
 * Дані OSM, ODbL. Тут лежать лише рамки та назви — жодного обʼєднання з
 * власними шарами, тож ліцензійний контур [local.phantom.companion.core.geo.license]
 * не зачіпається.
 *
 * ## Друга таблиця: країни світу
 *
 * [WORLD_TABLE] — той самий формат рядка, той самий парсер, той самий метод
 * перевірки: рамка країни взята з межі OSM, і кожен рядок звірений із
 * координатами столиці, отриманими ОКРЕМИМ запитом. Столиця поза рамкою
 * означає, що рамка не та, — такий рядок у таблицю не потрапив. Список
 * координат столиць лежить у `PlaceGazetteerTest`, не в APK: перевірка мусить
 * повторюватися на кожній збірці, а не бути обіцянкою в коментарі.
 *
 * Тайли планетарні (`tiles.openfreemap.org/planet`), тож завантажити чужу
 * країну можна вже сьогодні. Чого тут НЕМАЄ — областей і міст поза Україною:
 * жодного виміряного розміру такої виїмки ще немає, а возити невиміряне
 * заборонено. Пікер про це каже словом, а не мовчить.
 */
object PlaceGazetteer {

    val places: List<NamedPlace> by lazy { parse(PLACE_TABLE) }

    val country: NamedPlace? by lazy { places.firstOrNull { it.kind == PlaceKind.Country } }

    /** Рядок [WORLD_TABLE] має цей префікс — і саме за ним відрізняється оцінка розміру. */
    const val WORLD_ID_PREFIX = "w-"

    /**
     * Україна лежить у [WORLD_TABLE] нарівні з рештою, але тут її рядок
     * викидається: своя країна вже є в [PLACE_TABLE], з рамкою, порахованою з
     * полігонів областей, і з 314 місцями під нею. Два записи «Україна» в
     * одному списку — це два різні числа під однією назвою.
     */
    private const val LOCAL_COUNTRY_WORLD_ID = "${WORLD_ID_PREFIX}ua"

    /** Решта світу: рамки країн, без жодного місця всередині них. */
    val world: List<NamedPlace> by lazy {
        parse(WORLD_TABLE)
            .filter { it.kind == PlaceKind.Country && it.id != LOCAL_COUNTRY_WORLD_ID }
            .sortedWith(compareBy(ukrainian) { it.nameUa })
    }

    fun isWorldRow(place: NamedPlace): Boolean = place.id.startsWith(WORLD_ID_PREFIX)

    /**
     * Країни, чия рамка — МАТЕРИКОВА частина, а не рамка всієї межі OSM.
     *
     * У цих тринадцяти держав межа тягне за собою заморські володіння або
     * перетинає антимеридіан, і рамка виходить на пів планети: Франція від
     * -178° до 172°, США й Росія від -180° до 180°. З такої рамки не
     * завантажується нічого — планер чесно відмовляє, і країна стає
     * недоступною замість того, щоб бути доступною материком.
     *
     * `osmRef` у цих рядках і далі вказує на правильне відношення — це та сама
     * країна; звужена саме РАМКА. Мовчати про це не можна, тому пікер каже це
     * словом, а тест стежить, щоб список не ріс сам собою.
     */
    val MAINLAND_FRAMED: Set<String> = setOf(
        "w-au", "w-gb", "w-ki", "w-nl", "w-nz", "w-no", "w-za",
        "w-ru", "w-us", "w-tv", "w-fj", "w-fr", "w-cl",
    )

    fun isMainlandFramed(place: NamedPlace): Boolean = place.id in MAINLAND_FRAMED

    /**
     * Чи описано цю країну зсередини — області, райони, міста.
     *
     * Сьогодні таке є рівно в однієї країни. Питання ставиться до довідника, а
     * не вирішується мовчки в UI: коли ярус B привезе першу чужу країну, тут
     * зміниться одне місце, а не три екрани.
     */
    fun hasDetail(place: NamedPlace): Boolean =
        place.kind == PlaceKind.Country && places.any { it.parentUa != null && it.id != place.id } &&
            !isWorldRow(place)

    /** Області країни — порожньо там, де ярусу B ще немає. */
    fun oblastsOf(place: NamedPlace): List<NamedPlace> =
        if (!hasDetail(place)) emptyList()
        else places.filter { it.kind == PlaceKind.Oblast }.sortedWith(compareBy(ukrainian) { it.nameUa })

    /** Райони й міста області. */
    fun childrenOf(parent: NamedPlace): List<NamedPlace> =
        places.filter { it.parentUa == parent.nameUa }
            .sortedWith(compareBy<NamedPlace> { it.kind.ordinal }.thenBy(ukrainian) { it.nameUa })

    /**
     * Верхній рівень пікера. Країна, у якій телефон стоїть зараз, іде першою —
     * без фікса першою лишається Україна, бо вона й є описана країна.
     */
    fun countries(atLat: Double? = null, atLon: Double? = null): List<NamedPlace> {
        val all = listOfNotNull(country) + world
        if (atLat == null || atLon == null) return all
        val here = all.firstOrNull { it.contains(atLat, atLon) } ?: return all
        return listOf(here) + all.filterNot { it.id == here.id }
    }

    fun byId(id: String): NamedPlace? =
        places.firstOrNull { it.id == id } ?: world.firstOrNull { it.id == id }

    /**
     * Пошук за назвою. Порожній запит — не порожня видача: країна, області,
     * а далі найбільші міста, бо саме це оператор шукає найчастіше.
     */
    fun search(query: String, limit: Int = 40): List<NamedPlace> {
        val q = fold(query)
        if (q.isEmpty()) {
            return places
                .sortedWith(compareBy({ it.kind.ordinal }, { -it.boxAreaKm2 }))
                .take(limit)
        }
        return places
            .mapNotNull { p -> rank(p, q)?.let { it to p } }
            .sortedWith(compareBy({ it.first }, { it.second.kind.ordinal }, { -it.second.boxAreaKm2 }))
            .map { it.second }
            .take(limit)
    }

    /**
     * Пошук по всьому, що є: Україна зсередини плюс країни світу. Українські
     * місця стоять перед однаково влучними країнами — той, хто набирає три
     * літери, майже завжди шукає землю під ногами.
     */
    fun searchEverywhere(query: String, limit: Int = 60): List<NamedPlace> {
        val q = fold(query)
        if (q.isEmpty()) return countries().take(limit)
        val local = places.mapNotNull { p -> rank(p, q)?.let { it to p } }
        val abroad = world.mapNotNull { p -> rank(p, q)?.let { (it + WORLD_RANK_PENALTY) to p } }
        return (local + abroad)
            .sortedWith(compareBy({ it.first }, { it.second.kind.ordinal }, { -it.second.boxAreaKm2 }))
            .map { it.second }
            .take(limit)
    }

    /** Місця, що містять точку — від найменшого до найбільшого. */
    fun covering(lat: Double, lon: Double): List<NamedPlace> =
        places.filter { it.contains(lat, lon) }.sortedBy { it.boxAreaKm2 }

    /**
     * Те саме, але з падінням на країни світу. Потрібне іменуванню: клапоть,
     * збережений під Варшавою, до цього звався парою координат.
     */
    fun coveringAnywhere(lat: Double, lon: Double): List<NamedPlace> =
        covering(lat, lon).ifEmpty { world.filter { it.contains(lat, lon) }.sortedBy { it.boxAreaKm2 } }

    /**
     * Наскільки країна світу поступається українському місцю за однакового
     * збігу. Більше за найгірший ранг [rank], тож точний збіг по чужій країні
     * усе одно стоїть попереду часткового збігу — але позаду свого точного.
     */
    private const val WORLD_RANK_PENALTY = 10

    /** Порядок «за алфавітом» для української збірки: «Ї» після «І», а не після «Я». */
    private val ukrainian: java.util.Comparator<in String> by lazy {
        java.text.Collator.getInstance(java.util.Locale("uk", "UA"))
    }

    private fun rank(p: NamedPlace, q: String): Int? {
        val ua = fold(p.nameUa)
        val en = fold(p.nameEn)
        return when {
            ua == q || en == q -> 0
            ua.startsWith(q) || en.startsWith(q) -> 1
            ua.contains(q) || en.contains(q) -> 2
            p.parentUa != null && fold(p.parentUa).startsWith(q) -> 3
            else -> null
        }
    }

    /**
     * Апостроф в українських назвах пишуть чотирма різними символами
     * («Міжгірʼя», «Знамʼянка»), і клавіатура телефона дає пʼятий. Без цього
     * згортання половина міст не знаходиться за власною назвою.
     */
    fun fold(s: String): String = buildString(s.length) {
        for (ch in s.lowercase()) {
            when (ch) {
                '\'', '’', 'ʼ', '‘', '`', '´' -> Unit
                'ё' -> append('е')
                else -> append(ch)
            }
        }
    }.trim()

    internal fun parse(table: String): List<NamedPlace> = table.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val f = line.split('|')
            if (f.size < 11) return@mapNotNull null
            val kind = when (f[0]) {
                "C" -> PlaceKind.Country
                "O" -> PlaceKind.Oblast
                "R" -> PlaceKind.Raion
                "M" -> PlaceKind.City
                else -> return@mapNotNull null
            }
            NamedPlace(
                id = f[1],
                nameUa = f[2],
                nameEn = f[3],
                parentUa = f[4].ifEmpty { null },
                kind = kind,
                northLat = f[5].toDoubleOrNull() ?: return@mapNotNull null,
                eastLon = f[6].toDoubleOrNull() ?: return@mapNotNull null,
                southLat = f[7].toDoubleOrNull() ?: return@mapNotNull null,
                westLon = f[8].toDoubleOrNull() ?: return@mapNotNull null,
                landAreaKm2 = f[9].toDoubleOrNull(),
                osmRef = f[10].ifEmpty { null },
            )
        }
        .filter { it.valid }
        .toList()
}
