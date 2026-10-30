package local.phantom.companion.core.geo.metro

import java.util.Locale
import kotlin.math.roundToInt
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.geo.GeoMath
import local.phantom.companion.core.geo.layers.LayerCatalog
import local.phantom.companion.core.geo.layers.defaultTtlMs
import local.phantom.companion.core.geo.license.LicenseFirewall

/**
 * Чим закінчилась спроба зібрати схему станції.
 *
 * Кожен варіант має готовий український [summary]: це не «повідомлення про
 * помилку», а відповідь оператору, і вона мусить бути однаково чесною і коли
 * все вийшло, і коли ні. Саме тому «мережа мовчить» ([Unavailable]) і «в OSM
 * цієї станції немає» ([NoIndoorData]) — РІЗНІ варіанти: перше означає «я не
 * знаю», друге — «я знаю, що там порожньо», і плутати їх означає навчити
 * людину не вірити застосунку під землею, де перевірити нічим.
 */
sealed interface MetroStationResult {

    /** Текст для оператора. Готовий до показу, без подальшого форматування. */
    val summary: String

    /** Схему зібрано, шар лежить на мапі. */
    data class Mapped(
        override val summary: String,
        val layerId: String,
        val stationName: String?,
        val entranceCount: Int,
        /** Скільки входів мають `wheelchair=yes` — тобто гарантовано без сходів. */
        val accessibleEntranceCount: Int,
        val nearestEntranceRef: String?,
        val nearestEntranceDistanceM: Int,
        /** Час маршруту всередині станції, секунди; `null` — маршруту немає. */
        val routeSeconds: Double?,
        val routeLengthM: Double?,
        val routeUsesElevator: Boolean,
        /** Чи існує маршрут до платформи для людини у візку. */
        val wheelchairRouteExists: Boolean,
        val attribution: String,
        val focusLat: Double,
        val focusLon: Double,
    ) : MetroStationResult

    /** Overpass відповів, але підземного простору тут немає. Шар НЕ покладено. */
    data class NoIndoorData(override val summary: String, val radiusM: Int) : MetroStationResult

    /** Жодне дзеркало не відповіло — ми не знаємо, що там. Шар НЕ покладено. */
    data class Unavailable(override val summary: String) : MetroStationResult

    /**
     * Ліцензійний фаєрвол заборонив шар. Не теоретична гілка: якщо колись
     * хтось змінить картку шару в каталозі так, що вона порушить умови
     * джерела, продукт має ПЕРЕСТАТИ малювати цей шар, а не малювати його
     * далі з поламаною атрибуцією.
     */
    data class LicenseBlocked(
        override val summary: String,
        val violations: List<String>,
    ) : MetroStationResult
}

/**
 * Схема станції метро як живий шар карти.
 *
 * НАВІЩО ЦЕ ВЗАГАЛІ Є. Під землею супутника немає — саме там класична
 * навігація закінчується, а потреба в ній не зникає: у Києві та Харкові метро
 * є ще й основним укриттям, і питання «який вхід найближчий, чи є там ліфт,
 * скільки йти до платформи» — це питання хвилин, а іноді й безпеки. OSM
 * (разом з OpenStationMap) цей простір мапить: входи з номерами на табличках,
 * ескалатори з напрямком руху, ліфти, рівні. Модуль [IndoorGraph] уміє по
 * ньому рахувати шлях у СЕКУНДАХ з урахуванням доступності — але доти, доки
 * ніхто не викликає ні його, ні [OsmIndoorParser], усе це просто вага в APK.
 * Цей клас — місток: Overpass → граф → шар, який видно на екрані.
 *
 * ЧОМУ ЧЕРЕЗ [SpatialLayerSink], А НЕ ЧЕРЕЗ ВЛАСНИЙ UI. У застосунку вже є
 * повний конвеєр шарів: сховище з TTL, чипи «ACTIVE PHANTOM CONTEXT» з
 * фокусом/приховуванням/видаленням, рендер маркерів і ліній на MapLibre,
 * тап по маркеру з показом `detail`. Другий конвеєр поруч із цим був би не
 * інтеграцією, а ще однією мертвою гілкою.
 *
 * ЛІЦЕНЗІЯ. Шар оголошено в [LayerCatalog] під `metro-indoor` (ODbL,
 * `joinsOsmTopology = false`), і перед КОЖНИМ показом картка проходить
 * [LicenseFirewall] у рантаймі — не лише в тесті. Граф будується виключно з
 * елементів OSM; жодні власні дані PHANTOM у нього не вливаються, тож
 * топологічного обʼєднання, яке перетворило б комерційну базу на похідну,
 * не відбувається — і це властивість коду, а не обіцянка в README. Текст
 * атрибуції береться з каталогу і потрапляє і у відповідь оператору, і в
 * `detail` найближчого входу — тобто туди, куди людина справді дивиться.
 */
class MetroStationLayer internal constructor(
    private val sink: SpatialLayerSink,
    private val source: StationIndoorSource,
    private val ownerThreadId: () -> String?,
) {

    /**
     * Бойовий конструктор: справжній Overpass через дзеркала.
     *
     * @param ownerThreadId нитка діалогу, якій належатиме шар — щоб «прибери
     *   все, що ти наклав» прибирало саме її шари.
     */
    @JvmOverloads
    constructor(
        sink: SpatialLayerSink,
        ownerThreadId: () -> String? = { null },
    ) : this(sink, OverpassStationSource(), ownerThreadId)

    /**
     * Зібрати схему станції навколо точки і покласти її на мапу.
     *
     * @param radiusM радіус запиту. За замовчуванням [DEFAULT_RADIUS_M]: цього
     *   вистачає, щоб накрити вестибюлі й виходи однієї станції, і замало, щоб
     *   притягнути пів району пішохідних доріжок.
     * @param requireWheelchair будувати маршрут ЛИШЕ безбарʼєрний. Якщо такого
     *   немає — лінія не малюється зовсім: підмінити його маршрутом сходами
     *   означало б відправити людину у візку в глухий кут.
     */
    suspend fun summon(
        latDeg: Double,
        lonDeg: Double,
        radiusM: Int = DEFAULT_RADIUS_M,
        requireWheelchair: Boolean = false,
    ): MetroStationResult {
        // — Ліцензійний контур. Спершу, до будь-якої мережі: якщо шар не має
        //   права бути показаним, немає сенсу за нього ще й платити трафіком.
        val catalog = LayerCatalog.byId(LAYER_ID)
            ?: return MetroStationResult.LicenseBlocked(
                summary = "Шар «$LAYER_ID» не оголошено в каталозі джерел — показувати його не можна.",
                violations = listOf("layer-not-declared"),
            )
        val violations = LicenseFirewall.audit(listOf(catalog.layer))
        if (violations.isNotEmpty()) {
            return MetroStationResult.LicenseBlocked(
                summary = "Шар «${catalog.layer.title}» заблоковано ліцензійним контуром: " +
                    violations.joinToString("; ") { it.detail },
                violations = violations.map { it.rule },
            )
        }
        val attribution = catalog.layer.attribution

        val radius = radiusM.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
        val elements = source.fetch(IndoorOverpass.stationQuery(latDeg, lonDeg, radius))
            ?: return MetroStationResult.Unavailable(
                "Overpass не відповідає — схему станції зараз не завантажити. " +
                    "Це збій звʼязку, а не відсутність станції.",
            )

        val graph = OsmIndoorParser().parse(elements)
        val entrances = graph.entrances()
        val platforms = graph.nodes.filter { it.kind == IndoorNodeKind.PLATFORM }

        if (entrances.isEmpty() && platforms.isEmpty()) {
            return MetroStationResult.NoIndoorData(
                summary = "У радіусі $radius м OSM не має ні входів у метро, ні платформ. " +
                    "Внутрішньої схеми цієї станції в даних просто немає.",
                radiusM = radius,
            )
        }

        val nearest = graph.nearestEntrance(latDeg, lonDeg)
            ?: entrances.firstOrNull()
        val accessibleCount = entrances.count { it.wheelchair == Wheelchair.YES }
        val stationName = stationNameOf(entrances + platforms)

        // — Маршрут усередині станції. Рахуємо ОБИДВА варіанти завжди: питання
        //   «а у візку туди можна?» має відповідь незалежно від того, як їде
        //   той, хто спитав.
        //   Безбарʼєрний маршрут будується від НАЙБЛИЖЧОГО ДОСТУПНОГО входу, а
        //   не від найближчого взагалі: ліфт зазвичай стоїть при одному
        //   конкретному вестибюлі, і почати рахунок від входу зі сходами
        //   означало б отримати «шляху немає» там, де шлях є за сто метрів.
        val nearestAccessible = graph.nearestEntrance(latDeg, lonDeg, accessibleOnly = true)
        val wheelStart = nearestAccessible ?: nearest
        val target = nearest?.let { e ->
            platforms.minByOrNull { GeoMath.distanceM(e.latDeg, e.lonDeg, it.latDeg, it.lonDeg) }
        }
        val walkRoute = if (nearest != null && target != null) {
            graph.route(nearest.id, target.id)
        } else {
            null
        }
        val wheelRoute = if (wheelStart != null && target != null) {
            graph.route(wheelStart.id, target.id, IndoorRouteOptions(requireWheelchair = true))
        } else {
            null
        }
        val drawnRoute = if (requireWheelchair) wheelRoute else walkRoute
        // Рекомендований вхід — той, від якого реально піде людина, що спитала:
        // у безбарʼєрному режимі це доступний вхід, а не просто найближчий.
        val recommended = if (requireWheelchair) wheelStart else nearest

        val markers = buildMarkers(
            entrances = entrances,
            platforms = platforms,
            nearest = recommended,
            fromLat = latDeg,
            fromLon = lonDeg,
            attribution = attribution,
        )
        val lines = buildRouteLine(graph, drawnRoute)

        if (markers.isEmpty() && lines.isEmpty()) {
            return MetroStationResult.NoIndoorData(
                summary = "У радіусі $radius м знайдено підземні обʼєкти, але жодного входу чи " +
                    "платформи, які можна показати. Малювати нічого.",
                radiusM = radius,
            )
        }

        val spec = SpatialLayerSpec(
            title = listOfNotNull("метро", stationName).joinToString(" · "),
            origin = LayerOrigin.WORLD,
            kind = LAYER_KIND,
            ownerThreadId = ownerThreadId(),
            markers = markers,
            lines = lines,
            color = COLOR_LAYER,
            glyph = "метро",
            // TTL — властивість способу оновлення шару, з каталогу, а не
            // вигадане тут число (див. UpdateMethod.defaultTtlMs).
            ttlMs = catalog.update.defaultTtlMs,
        )

        val layerId = runCatching { sink.push(spec) }.getOrNull()
            ?: return MetroStationResult.Unavailable(
                "Не вдалося покласти шар станції на мапу.",
            )

        val focus = recommended ?: platforms.first()
        runCatching {
            sink.requestFocus(
                FocusTarget(
                    lat = focus.latDeg,
                    lon = focus.lonDeg,
                    // Зум із каталогу: нижче minZoom шар не має сенсу — маркери
                    // входів злипаються в одну пляму.
                    zoom = catalog.minZoom.toDouble(),
                    layerId = layerId,
                ),
            )
        }

        return MetroStationResult.Mapped(
            summary = summarize(
                stationName = stationName,
                radiusM = radius,
                entrances = entrances.size,
                accessible = accessibleCount,
                nearest = recommended,
                nearestDistanceM = recommended?.let {
                    GeoMath.distanceM(latDeg, lonDeg, it.latDeg, it.lonDeg).roundToInt()
                } ?: 0,
                platforms = platforms.size,
                walkRoute = walkRoute,
                wheelRoute = wheelRoute,
                requireWheelchair = requireWheelchair,
                attribution = attribution,
            ),
            layerId = layerId,
            stationName = stationName,
            entranceCount = entrances.size,
            accessibleEntranceCount = accessibleCount,
            nearestEntranceRef = recommended?.ref,
            nearestEntranceDistanceM = recommended?.let {
                GeoMath.distanceM(latDeg, lonDeg, it.latDeg, it.lonDeg).roundToInt()
            } ?: 0,
            routeSeconds = drawnRoute?.totalSeconds,
            routeLengthM = drawnRoute?.totalLengthM,
            routeUsesElevator = drawnRoute?.uses(IndoorEdgeKind.ELEVATOR) == true,
            wheelchairRouteExists = wheelRoute != null,
            attribution = attribution,
            focusLat = focus.latDeg,
            focusLon = focus.lonDeg,
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Побудова геометрії шару
    // ————————————————————————————————————————————————————————————————

    /**
     * Маркери входів і платформ.
     *
     * Найближчий вхід фарбується окремо і отримує префікс «★»: у переході з
     * шістьма виходами однаково пофарбовані крапки не відповідають на єдине
     * питання, яке людина ставить — «мені в котрий?».
     */
    private fun buildMarkers(
        entrances: List<IndoorNode>,
        platforms: List<IndoorNode>,
        nearest: IndoorNode?,
        fromLat: Double,
        fromLon: Double,
        attribution: String,
    ): List<SpatialMarker> {
        val out = mutableListOf<SpatialMarker>()

        entrances
            .sortedBy { GeoMath.distanceM(fromLat, fromLon, it.latDeg, it.lonDeg) }
            .take(MAX_ENTRANCE_MARKERS)
            .forEach { e ->
                val distM = GeoMath.distanceM(fromLat, fromLon, e.latDeg, e.lonDeg).roundToInt()
                val isNearest = nearest != null && e.id == nearest.id
                val label = buildString {
                    if (isNearest) append("★ ")
                    append("вхід")
                    e.ref?.takeIf { it.isNotBlank() }?.let { append(" №").append(it) }
                }
                val detail = buildString {
                    append(distM).append(" м · ").append(wheelchairText(e.wheelchair))
                    e.name?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    // Атрибуція ODbL їде на найближчому вході — тобто саме на
                    // тому маркері, який людина відкриє. Дублювати її на всіх
                    // маркерах означало б перетворити обовʼязкову вимогу на шум,
                    // який ніхто не читає.
                    if (isNearest && attribution.isNotBlank()) {
                        append(" · ").append(attribution)
                    }
                }
                out += SpatialMarker(
                    lat = e.latDeg,
                    lon = e.lonDeg,
                    label = label,
                    glyph = "вхід метро",
                    color = when {
                        isNearest -> COLOR_NEAREST
                        e.wheelchair == Wheelchair.YES -> COLOR_ACCESSIBLE
                        else -> COLOR_ENTRANCE
                    },
                    detail = detail,
                )
            }

        platforms.take(MAX_PLATFORM_MARKERS).forEach { p ->
            out += SpatialMarker(
                lat = p.latDeg,
                lon = p.lonDeg,
                label = p.name?.takeIf { it.isNotBlank() } ?: "платформа",
                glyph = "платформа",
                color = COLOR_PLATFORM,
                detail = levelText(p.level),
            )
        }
        return out
    }

    /** Лінія маршруту всередині станції; порожньо, якщо маршруту немає. */
    private fun buildRouteLine(graph: IndoorGraph, route: IndoorRoute?): List<SpatialLine> {
        val pts = route?.nodeIds.orEmpty().mapNotNull { graph.node(it) }
            .map { GeoPoint(it.latDeg, it.lonDeg) }
        if (pts.size < 2) return emptyList()
        return listOf(
            SpatialLine(
                points = pts,
                color = COLOR_ROUTE,
                width = 5f,
                label = "перехід у станції",
            ),
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Текст для оператора
    // ————————————————————————————————————————————————————————————————

    private fun summarize(
        stationName: String?,
        radiusM: Int,
        entrances: Int,
        accessible: Int,
        nearest: IndoorNode?,
        nearestDistanceM: Int,
        platforms: Int,
        walkRoute: IndoorRoute?,
        wheelRoute: IndoorRoute?,
        requireWheelchair: Boolean,
        attribution: String,
    ): String = buildString {
        append(stationName?.let { "Станція «$it»" } ?: "Станція метро")
        append(": ")
        if (entrances > 0) {
            append(entrances).append(' ')
                .append(plural(entrances, "вхід", "входи", "входів"))
            append(" у радіусі ").append(radiusM).append(" м")
            append(
                if (accessible > 0) {
                    ", $accessible з них без сходів"
                } else {
                    ", жодного з підтвердженою доступністю"
                },
            )
        } else {
            append("входів у даних немає, знайдено ").append(platforms).append(' ')
                .append(plural(platforms, "платформу", "платформи", "платформ"))
        }
        append(".")

        if (nearest != null) {
            append("\nНайближчий — ")
            append(nearest.ref?.takeIf { it.isNotBlank() }?.let { "вхід №$it" } ?: "вхід без номера")
            append(", ").append(nearestDistanceM).append(" м від точки.")
        }

        when {
            walkRoute != null -> {
                // «Пішки» сказано навмисно: у безбарʼєрному режимі намальовано
                // ІНШИЙ маршрут, і рядок нижче про візок — саме про нього.
                append("\nПішки всередині: ").append(walkRoute.totalLengthM.roundToInt()).append(" м, ")
                append(durationText(walkRoute.totalSeconds))
                if (walkRoute.levelChanges > 0) {
                    append(", ").append(walkRoute.levelChanges).append(' ')
                        .append(plural(walkRoute.levelChanges, "зміна рівня", "зміни рівня", "змін рівня"))
                }
                append(".")
            }

            platforms > 0 ->
                append("\nВходи є, але звʼязного проходу до платформи в OSM не намальовано — маршрут усередині не будую.")

            else ->
                append("\nПлатформ у даних немає — тільки входи.")
        }

        if (platforms > 0) {
            append("\nУ візку: ")
            append(
                if (wheelRoute != null) {
                    "маршрут до платформи є" +
                        (if (wheelRoute.uses(IndoorEdgeKind.ELEVATOR)) " — через ліфт" else "") +
                        ", " + durationText(wheelRoute.totalSeconds) + "."
                } else {
                    "за даними OSM до платформи не потрапити — безбарʼєрного шляху в схемі немає."
                },
            )
            if (requireWheelchair && wheelRoute == null) {
                append(" Лінію не малюю: показати замість неї маршрут сходами було б брехнею.")
            }
        }

        if (attribution.isNotBlank()) append("\n").append(attribution)
    }

    private companion object {
        /** Ідентифікатор шару в [LayerCatalog] — джерело ліцензії та зумів. */
        const val LAYER_ID = "metro-indoor"

        /** Родина шару в сховищі; за нею чипи й фільтри відрізняють метро. */
        const val LAYER_KIND = "metro"

        const val DEFAULT_RADIUS_M = 300
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 800

        /** Стеля маркерів: вузол пересадки не має перетворити мапу на кашу. */
        const val MAX_ENTRANCE_MARKERS = 24
        const val MAX_PLATFORM_MARKERS = 12

        const val COLOR_LAYER = "#38BDF8"
        const val COLOR_ENTRANCE = "#38BDF8"
        const val COLOR_ACCESSIBLE = "#22C55E"
        const val COLOR_NEAREST = "#F59E0B"
        const val COLOR_PLATFORM = "#A855F7"
        const val COLOR_ROUTE = "#22D3EE"

        /** Назва станції — найчастіша серед підписаних вершин. */
        fun stationNameOf(nodes: List<IndoorNode>): String? =
            nodes.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key

        fun wheelchairText(w: Wheelchair): String = when (w) {
            Wheelchair.YES -> "без сходів"
            Wheelchair.LIMITED -> "частково доступний"
            Wheelchair.NO -> "у візку не пройти"
            Wheelchair.UNKNOWN -> "доступність невідома"
        }

        fun levelText(level: Double): String {
            val raw = if (level == level.roundToInt().toDouble()) {
                level.roundToInt().toString()
            } else {
                String.format(Locale.US, "%.1f", level).replace('.', ',')
            }
            return "рівень " + raw.replace('-', '−')
        }

        fun durationText(seconds: Double): String {
            val s = seconds.roundToInt()
            return if (s < 90) "≈$s с" else "≈${(s / 60.0).roundToInt()} хв"
        }

        /**
         * Українська множина. Окремою функцією, бо «5 входи» в бойовому
         * застосунку читається як недороблений прототип, а не як дрібниця.
         */
        fun plural(n: Int, one: String, few: String, many: String): String {
            val mod100 = n % 100
            val mod10 = n % 10
            return when {
                mod100 in 11..14 -> many
                mod10 == 1 -> one
                mod10 in 2..4 -> few
                else -> many
            }
        }
    }
}
