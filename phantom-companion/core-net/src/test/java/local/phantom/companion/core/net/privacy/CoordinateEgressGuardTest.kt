package local.phantom.companion.core.net.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.SortedMap

/**
 * СТОРОЖ КООРДИНАТ У РЯДКУ АДРЕСИ.
 *
 * 20.09 вимір: `NominatimClient.reverse` мав три тіри — свій сервер, далі
 * `photon.komoot.io`, далі публічний Nominatim, усі три з `?lat=…&lon=…`
 * прямо в URL. Кличе це не людина: MapScreen геокодує центр кадру через 1,5 с
 * після кожного зсуву далі ніж на 200 м. Оператор возить мапу пальцем — чужий
 * сервер збирає доріжку його координат. Той самий шаблон, незалежно, стояв у
 * `ReverseGeocoder` застосунку, де фоновий прогрів ambient-заголовка робив те
 * саме за саму лише розмову в чаті.
 *
 * Сусідній [local.phantom.companion.privacy.OutboundHostAllowlistTest] (модуль
 * :app) веде перелік ХОСТІВ і не бачить цього класу вад: `photon.komoot.io`
 * стояв у ньому дозволеним, із позначкою «ДІЯ ЛЮДИНИ — геокодування за запитом
 * на мапі», і перелік лишався зелений увесь час, поки координати текли самі.
 * Хост — це «куди», а тут питання «ЩО туди їде».
 *
 * ДРУГИЙ ВИМІР, 20.09 — КООРДИНАТИ В ШЛЯХУ. Перша редакція цього сторожа
 * шукала координату лише в ПАРАМЕТРАХ і тому не бачила OSRM: той кладе обидві
 * точки в сам шлях (`/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat`).
 * А `GeoEndpoints.routingBasesFor` свідомо підставляла за LAN-шаблоном
 * публічний `routing.openstreetmap.de` — «щоб не падало поза Wi-Fi». Отже
 * щоразу, коли людина не вдома, чужий сервер отримував звідки І куди вона
 * їде. Форма інша, хвороба та сама.
 *
 * ПРАВИЛО: у `src/main` жодна адреса, побудована з зашитим зовнішнім хостом,
 * не несе координат — ні в параметрах, ні в шляху. Перевіряється не слово
 * «komoot» чи «openstreetmap» (перейменування обійшло б обидва), а ФОРМА:
 * «координата в адресі» + «основа, яку не можна вимкнути в налаштуваннях».
 *
 * Сторож іде в основу чотирма шляхами, бо рядок адреси рідко буває цілим:
 *   1. хост зашитий у самому літералі («https://…/reverse?lat=$lat»);
 *   2. літерал починається з підстановки, а та підстановка — це імʼя, яке
 *      десь у дереві дорівнює зашитому хостові (`$base` ← `PUBLIC_NOMINATIM`);
 *   3. основа приходить параметром функції — тоді перевіряються ВИКЛИКИ цієї
 *      функції по всьому дереву (саме так ховався
 *      `nominatimReverseUrl(PUBLIC_NOMINATIM, lat, lon, …)`);
 *   4. основу видає ФУНКЦІЯ-ПОСТАЧАЛЬНИК — оголошена як `: String?` чи
 *      `: List<String>`, — і десь у її тілі стоїть зашитий зовнішній хост.
 *      Тоді червоніє кожен файл, який і несе координати в адресі, і кличе
 *      того постачальника. Саме так ховався публічний OSRM: у місці, де
 *      будується URL, не видно ані хоста, ані навіть підстановки — лише
 *      `$base`, що прийшов із циклу двома сотнями рядків вище.
 *
 * Межа сторожа, названа вголос: гілка 4 впізнає постачальника за ОГОЛОШЕНИМ
 * типом результату і лише на верхньому рівні файла. Функція без оголошеного
 * типу, або метод усередині класу, повз неї пройдуть — це відоме вікно, а не
 * недогляд, і причина в першому абзаці про `baseProviderFun`.
 *
 * Що НЕ є вадою й лишається в [allowed] з причиною: основа з конфігурації
 * ([local.phantom.companion.core.net.map.GeoEndpoints]) — її оператор може
 * вимкнути або спрямувати на свій сервер; і адреси, які застосунок не
 * запитує сам, а віддає людині чи іншій програмі.
 */
class CoordinateEgressGuardTest {

    /** Ті самі модулі, що в settings.gradle.kts. */
    private val modules = listOf(
        "app", "core-ai", "core-context", "core-data", "core-design", "core-geo",
        "core-gnss", "core-indoor", "core-license", "core-mobility", "core-nav",
        "core-net", "core-sensor", "core-symbiote", "core-vision", "core-voice",
        "feature-ambient", "feature-car", "feature-godmode", "feature-onboarding",
        "feature-precision", "feature-stream", "tools/obsidian",
    )

    /**
     * Файл -> причина, чому координати в його адресі це не витік.
     *
     * Кожен рядок — свідоме рішення, не «щоб позеленіло». Новий запис має
     * пройти через людину, яка веде цей перелік.
     */
    private val allowed: Map<String, String> = mapOf(
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/PhotonSearch.kt" to
            "РІШЕННЯ D7 (MASTER 15.10) — пошук місць іде з телефона просто в Photon, без проксі. " +
                "Точка в запиті — лише `kmBias`: округлена до 0.01° (≈1 км), і PhotonSearchTest " +
                "це стереже. Точна точка (reverse) на публічний хост не йде: reverseAllowed = false.",
        "core-geo/src/main/java/local/phantom/companion/core/geo/link/MapLink.kt" to
            "НЕ ЗАПИТ ЗАСТОСУНКУ — посилання «відкрити точку в OSM», яке йде в " +
                "зовнішній браузер через Intent. Адресу відкриває людина своїм " +
                "натиском; застосунок її не запитує.",
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/cockpit/CarCockpitSheet.kt" to
            "НЕ ЗАПИТ ЗАСТОСУНКУ — текст «моє авто запарковано тут: …», який людина " +
                "сама надсилає обраному нею адресатові. Застосунок цей URL не відкриває.",
        "core-ai/src/main/java/local/phantom/companion/core/ai/external/OpenMeteoClient.kt" to
            "ВІДОМИЙ ВИТІК, НЕ ЦЕЙ ФРОНТ — погода: `api.open-meteo.com?latitude=…&longitude=…`, " +
                "основа зашита в DEFAULT_ENDPOINT і НЕ читається з GeoEndpoints. Кличе " +
                "WeatherTool, тобто лише коли асистента попросили про погоду — але точка " +
                "все одно точна і сервер усе одно чужий. Винести основу в конфігурацію й " +
                "огрубити точку — окреме рішення власника, записане у звіті 20.09.",
        "core-net/src/main/java/local/phantom/companion/core/net/map/PlaceDossierClient.kt" to
            "ДІЯ ЛЮДИНИ — досьє місця після тапу на мапі або прохання до асистента (PlaceInfoTool, " +
                "TransitHomeTool). Overpass отримує точну точку: зупинки шукаються в радіусі 80 м. " +
                "Вікіпедія — лише точку, округлену до 0.001° (PlaceDossierGeosearchTest). Проксі на сервері нема.",
        "core-net/src/main/java/local/phantom/companion/core/net/transit/TransitousTransitClient.kt" to
            "ДІЯ ЛЮДИНИ — маршрут громадським, який людина попросила (RoutePlanner). Початок і кінець " +
                "мають бути точні, інакше маршрут хибний. Проксі на сервері нема.",
        "core-net/src/main/java/local/phantom/companion/core/net/transit/TransitlandClient.kt" to
            "НЕ ДОСЯЖНИЙ — потрібен ключ phantom.transitlandKey, а TransitlandSource не передано жодному викликачу.",
        "core-symbiote/src/main/java/local/phantom/companion/core/tool/impl/GeoTool.kt" to
            "ДІЯ ЛЮДИНИ — geo.nearby: POST в Overpass із точкою, яку назвала людина або асистент на її " +
                "прохання. Overpass задекларовано в Data Safety.",
        "core-symbiote/src/main/java/local/phantom/companion/core/tool/impl/TransitTools.kt" to
            "ДІЯ ЛЮДИНИ — transit.nearby / transit.routes: POST в Overpass на прохання асистентові або " +
                "тап у меню «світ» на мапі. Overpass задекларовано в Data Safety.",
        "core-symbiote/src/main/java/local/phantom/companion/core/tool/impl/WorldSensorTools.kt" to
            "ВІДОМИЙ ВИТІК, НЕ ЦЕЙ ФРОНТ — літаки навколо: `api.adsb.lol/v2/lat/…/lon/…/dist/…`, " +
                "основа зашита в ADSB_ENDPOINT. Кличе інструмент асистента на прохання " +
                "людини; та сама розмова, що й про погоду вище.",
    )

    // ── форми ────────────────────────────────────────────────────────────────

    /** Локальна мережа й приклади з KDoc — не «чужий бік». */
    private val localHost = Regex(
        """^(localhost|127\.|10\.|172\.|192\.168\.|phantom\.local|relay\.example|example\.invalid)""",
    )
    private val hostIn = Regex("""https?://([A-Za-z0-9._-]+)""")

    /**
     * Параметр, що несе координату. Третя гілка ловить форму «?q=$lat,$lon» —
     * пару підстановок під будь-яким імʼям параметра, бо імʼя «q» нічого не
     * приховує від сервера на тому кінці.
     */
    private val coordParam = Regex(
        """[?&]\s*(lat|lon|lng|latitude|longitude|mlat|mlon|viewbox|bbox|gscoord|fromPlace|toPlace|proximity|point)\s*=""" +
            """|^\s*(gscoord|fromPlace|toPlace|proximity|viewbox|bbox)\s*=\s*\$""" +
            """|\baround:\s*[\w${'$'}{}.()]+\s*,\s*\$""" +
            """|/(lat|lon|lng)/\s*\$""" +
            """|[?&][A-Za-z_]+=\$\{?[\w.()]+\}?\s*[,;|]\s*\$""",
    )

    /**
     * Координата в ШЛЯХУ: пара підстановок через кому на початку літерала або
     * відразу за `/` чи `;`. Це форма OSRM (`$lon,$lat;$lon,$lat`) і форма
     * будь-якого сервісу, що бере точку позиційно. Імена підстановок не
     * читаються навмисно: `$a,$b` приховує від сервера рівно нічого.
     */
    private val coordPath = Regex("""(?:^|[/;])\s*\$\{?[\w.()]+\}?\s*,\s*\$\{?[\w.()]+\}?""")

    /** Рядковий літерал Kotlin: спершу потрійні лапки, потім звичайні. */
    private val literal = Regex(""""{3}([\s\S]*?)"{3}|"((?:[^"\\\n]|\\.)*)"""")

    private val topLevelHostConst = Regex(
        """^\s*(?:internal\s+|private\s+|public\s+)?const\s+val\s+([A-Za-z_]\w*)\s*(?::\s*String\s*)?=\s*"(https?://[^"$]+)"""",
        RegexOption.MULTILINE,
    )
    private val valBinding = Regex(
        """\bval\s+([A-Za-z_]\w*)\s*(?::\s*(?:String|List<String>)\s*)?=\s*(?:"(https?://[^"$]*)"|([A-Za-z_][\w.]*))""",
    )
    private val hostListBinding = Regex(
        """\bval\s+([A-Za-z_]\w*)\s*(?::\s*List<String>\s*)?=\s*listOf\(\s*"(https?://[^"$]+)"""",
    )
    private val loopBinding = Regex("""\bfor\s*\(\s*([A-Za-z_]\w*)\s+in\s+([A-Za-z_][\w.]*)\s*\)""")
    private val urlHead = Regex("""^\$\{?([A-Za-z_]\w*)\}?[/?]""")
    private val postCall = Regex("""\.post\w*\s*\(\s*([^,()\n]+?)\s*(?:,\s*([^\n]*)|\)\s*\{)""")
    private val coordJson = Regex("""\bput\(\s*"(lat|lon|lng|latitude|longitude)"|"(lat|lon|lng|latitude|longitude)"\s+to\b""")
    private val anyBinding = Regex("""\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=(?!=)""")
    private val ownerDecl = Regex("""\b(?:class|object)\s+([A-Za-z_]\w*)""")

    /**
     * Функція, чий ОГОЛОШЕНИЙ результат — це URL-основа (гілка 4). Лише
     * верхній рівень: гілка звʼязує оголошення з викликом по голому імені, а
     * імена методів усередині класів повторюються по дереву (`start`,
     * `refresh`, `route`, `own` — заміряно 20.09, шість різних класів), і
     * кожен такий збіг червонив би невинний файл.
     */
    private val baseProviderFun = Regex(
        """^(?:internal\s+|private\s+|public\s+)?fun\s+(?:[A-Za-z_][\w.<>?]*\.)?([A-Za-z_]\w*)\s*\([^)]*\)\s*:\s*(?:String\??|List<String>\??)\s*(?:=|\{)""",
        RegexOption.MULTILINE,
    )

    private fun externalHostOrNull(url: String): String? =
        hostIn.find(url)?.groupValues?.get(1)?.takeIf { !localHost.containsMatchIn(it) }

    private fun textOf(m: MatchResult): String = m.groupValues[1].ifEmpty { m.groupValues[2] }

    private fun carriesCoord(s: String): Boolean =
        coordParam.containsMatchIn(s) || coordPath.containsMatchIn(s)

    /** Тіло оголошення: від його кінця до наступного оголошення без відступу. */
    private fun bodyAfter(text: String, from: Int): String {
        val rest = text.substring(from)
        val end = Regex(
            """\n(?:@|const\s|val\s|var\s|fun\s|class\s|object\s|enum\s|interface\s|private\s|internal\s|/\*\*)""",
        ).find(rest, 1)?.range?.first
        return if (end == null) rest else rest.take(end)
    }

    // ── розбір ───────────────────────────────────────────────────────────────

    /**
     * Увесь розбір над готовими джерелами (шлях -> текст). Винесено з тесту
     * навмисно: інакше «сторож уміє червоніти» лишається обіцянкою в коміті —
     * а так це прогін над підробленим деревом, видимий у третьому тесті.
     */
    private fun scan(sources: Map<String, String>, honourAllowed: Boolean = true): SortedMap<String, String> {
        val found = sortedMapOf<String, String>()
        fun flag(path: String, line: Int, host: String, what: String) {
            if (honourAllowed && path in allowed) return
            found["$path:$line"] = "$host  ←  ${what.take(110)}"
        }
        fun lineOf(text: String, at: Int) = text.take(at).count { it == '\n' } + 1

        fun constsOf(text: String): Map<String, String> = topLevelHostConst.findAll(text)
            .mapNotNull { m -> externalHostOrNull(m.groupValues[2])?.let { m.groupValues[1] to it } }
            .toMap()

        // Імена, що по всьому дереву означають зашитий зовнішній хост. Одне імʼя в
        // кількох файлах — усі хости через «|», а не той, що випадково пізніший.
        val globalNames: Map<String, String> = sources.values
            .flatMap { constsOf(it).entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, hosts) -> hosts.distinct().sorted().joinToString(" | ") }
        val ownerConsts: Map<String, Map<String, String>> = buildMap {
            sources.values.forEach { t ->
                val consts = constsOf(t)
                if (consts.isNotEmpty()) ownerDecl.findAll(t).forEach { put(it.groupValues[1], consts) }
            }
        }

        val globalLists = buildMap {
            sources.values.forEach { t ->
                hostListBinding.findAll(t).forEach { m ->
                    externalHostOrNull(m.groupValues[2])?.let { put(m.groupValues[1], it) }
                }
            }
        }

        fun nearest(r: Regex, text: String, n: String, at: Int): MatchResult? {
            val all = r.findAll(text).filter { it.groupValues[1] == n }.toList()
            return all.lastOrNull { it.range.first < at } ?: all.firstOrNull()
        }

        fun resolve(name: String, text: String, at: Int = text.length, depth: Int = 0): String? {
            val n = name.substringAfterLast('.')
            val fileConsts = constsOf(text)
            fun named(ref: String): String? {
                val owner = ref.substringBeforeLast('.', "").substringAfterLast('.')
                val bare = ref.substringAfterLast('.')
                return ownerConsts[owner]?.get(bare) ?: fileConsts[bare] ?: globalNames[bare] ?: globalLists[bare]
            }
            val local = nearest(valBinding, text, n, at)?.let { m ->
                externalHostOrNull(m.groupValues[2]) ?: named(m.groupValues[3])
            }
            val viaLoop = if (depth < 3) {
                nearest(loopBinding, text, n, at)?.let { resolve(it.groupValues[2], text, it.range.first, depth + 1) }
            } else null
            return local ?: viaLoop ?: named(name)
        }

        fun bodyCarries(
            expr: String,
            text: String,
            bindings: List<MatchResult>,
            at: Int,
            memo: MutableMap<Int, Boolean>,
            depth: Int = 0,
        ): Boolean {
            if (literal.findAll(expr).any { carriesCoord(textOf(it)) } || coordJson.containsMatchIn(expr)) return true
            if (depth >= 4) return false
            val ids = Regex("""[A-Za-z_]\w*""").findAll(expr).map { it.value }.toSet()
            return ids.any { id ->
                val b = bindings.lastOrNull { it.groupValues[1] == id && it.range.first < at } ?: return@any false
                memo.getOrPut(b.range.first) {
                    memo[b.range.first] = false
                    val rest = text.substring(b.range.last + 1)
                    val end = Regex("""\n\s*(?:val|var|return|if|for|while)\s""").find(rest)?.range?.first ?: rest.length
                    bodyCarries(rest.take(end), text, bindings, b.range.first, memo, depth + 1)
                }
            }
        }

        fun baseHost(arg: String, text: String, at: Int): String? {
            val a = arg.trim()
            Regex("""^"(https?://[^"]+)""").find(a)?.let { return externalHostOrNull(it.groupValues[1]) }
            urlHead.find(a.removePrefix("\""))?.let { return resolve(it.groupValues[1], text, at) }
            return a.takeIf { it.matches(Regex("""[\w.]+""")) }?.let { resolve(it, text, at) }
        }

        // ── гілка 4: постачальники основи ────────────────────────────────────
        val providerBodies = mutableMapOf<String, String>()
        sources.values.forEach { t ->
            baseProviderFun.findAll(t).forEach { m ->
                providerBodies[m.groupValues[1]] = bodyAfter(t, m.range.last)
            }
        }
        val providers = mutableMapOf<String, String>()
        providerBodies.forEach { (fn, body) ->
            val host = literal.findAll(body).mapNotNull { externalHostOrNull(textOf(it)) }.firstOrNull()
                ?: globalNames.entries
                    .firstOrNull { (n, _) -> Regex("""\b${Regex.escape(n)}\b(?!\s*\()""").containsMatchIn(body) }
                    ?.value
            if (host != null) providers[fn] = host
        }
        // Постачальник, що кличе постачальника, — теж постачальник.
        repeat(4) {
            providerBodies.forEach { (fn, body) ->
                if (fn !in providers) {
                    providers.entries
                        .firstOrNull { (p, _) -> Regex("""\b${Regex.escape(p)}\s*\(""").containsMatchIn(body) }
                        ?.let { providers[fn] = it.value }
                }
            }
        }

        // Функції, чия основа приходить параметром: імʼя -> імʼя параметра.
        val builders = mutableMapOf<String, String>()

        sources.forEach { (path, t) ->
            val lines = t.lines()
            var coordsHere = false
            literal.findAll(t).forEach { m ->
                val s = textOf(m)
                if (!carriesCoord(s)) return@forEach
                coordsHere = true
                val line = lineOf(t, m.range.first)
                // Вікно виразу: адреса часто склеєна з кількох рядків через `+`.
                val window = lines.subList((line - 5).coerceAtLeast(0), line).joinToString("\n")
                val inWindow = hostIn.findAll(window)
                    .map { it.groupValues[1] }
                    .firstOrNull { !localHost.containsMatchIn(it) }
                if (inWindow != null) {
                    flag(path, line, inWindow, s.trim())
                    return@forEach
                }
                val head = Regex("""^\$\{?([\w.]+)""").find(s.trim())?.groupValues?.get(1)
                    ?: Regex(""""\$\{?([A-Za-z_][\w.]*)""").findAll(window).lastOrNull()?.groupValues?.get(1)
                    ?: return@forEach
                val viaName = resolve(head, t, m.range.first)
                if (viaName != null) {
                    flag(path, line, "$viaName (через $head)", s.trim())
                    return@forEach
                }
                // Основа — параметр: запамʼятовуємо функцію, виклики нижче.
                Regex("""\bfun\s+(?:[A-Za-z_][\w.<>?]*\.)?([A-Za-z_]\w*)\s*\(""")
                    .findAll(t.take(m.range.first))
                    .lastOrNull()
                    ?.let { builders.putIfAbsent(it.groupValues[1], head) }
            }

            if (!coordsHere) return@forEach
            literal.findAll(t).forEach { m ->
                val head = urlHead.find(textOf(m))?.groupValues?.get(1) ?: return@forEach
                val host = resolve(head, t, m.range.first) ?: return@forEach
                flag(
                    path,
                    lineOf(t, m.range.first),
                    "$host (через $head)",
                    "файл несе координати й запитує зашиту основу: ${textOf(m).trim()}",
                )
            }
            providers.forEach { (fn, host) ->
                Regex("""\b${Regex.escape(fn)}\s*\(""").findAll(t).forEach { m ->
                    // Саме оголошення постачальника — не виклик.
                    if (t.take(m.range.first).trimEnd().endsWith("fun")) return@forEach
                    flag(
                        path,
                        lineOf(t, m.range.first),
                        "$host (основу видає $fn)",
                        "файл несе координати в адресі й бере основу з $fn",
                    )
                }
            }
        }

        sources.forEach { (path, t) ->
            val bindings by lazy { anyBinding.findAll(t).toList() }
            val memo = mutableMapOf<Int, Boolean>()
            postCall.findAll(t).forEach { m ->
                val host = baseHost(m.groupValues[1], t, m.range.first) ?: return@forEach
                val body = m.groupValues[2].ifEmpty { t.substring(m.range.last + 1).lines().take(20).joinToString("\n") }
                if (!bodyCarries(body, t, bindings, m.range.first, memo)) return@forEach
                flag(path, lineOf(t, m.range.first), "$host (тіло POST)", m.value.trim())
            }
        }

        builders.forEach { (fn, param) ->
            val call = Regex("""\b${Regex.escape(fn)}\s*\(\s*([^,)\n]+)""")
            sources.forEach { (path, t) ->
                call.findAll(t).forEach { m ->
                    val arg = m.groupValues[1].trim()
                    if (arg == param) return@forEach
                    val host = Regex("""^"(https?://[^"]+)"""").find(arg)?.groupValues?.get(1)
                        ?.let { externalHostOrNull(it) }
                        ?: arg.takeIf { it.matches(Regex("""[\w.]+""")) }?.let { resolve(it, t, m.range.first) }
                    if (host != null) {
                        flag(path, lineOf(t, m.range.first), "$host (аргумент $arg → $fn)", arg)
                    }
                }
            }
        }

        return found
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun treeSources(): Map<String, String> {
        val root = repoRoot()
        val files = modules.flatMap { m ->
            File(root, "$m/src/main").let { d ->
                if (d.isDirectory) d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                else emptyList()
            }
        }.sorted()
        // Дерево переїхало — зелене нижче не значить нічого.
        check(files.size > 100) { "підозріло мало .kt у src/main (${files.size})" }
        return files.associate { it.path.removePrefix(root.path + "/") to it.readText() }
    }

    @Test
    fun `жодна зашита зовнішня адреса не несе координат оператора`() {
        val found = scan(treeSources())
        assertEquals(
            "координати оператора їдуть на зашитий зовнішній хост (${found.size} місць). " +
                "Зворотне геокодування, погода, «що навколо», МАРШРУТ — усе це питання «як " +
                "зветься те, на що я дивлюсь» або «як мені туди», і жодне з них не варте " +
                "розкриття того, ДЕ оператор стоїть і КУДИ прямує. Основа має приходити з " +
                "GeoEndpoints (оператор може її вимкнути), а не бути зашитою чи підставленою " +
                "за спиною; якщо це справді не запит застосунку — впиши файл у `allowed` " +
                "З ПРИЧИНОЮ, не прибирай цей рядок:\n    " +
                found.entries.joinToString("\n    ") { "${it.key}\n        ${it.value}" },
            emptyMap<String, String>(),
            found,
        )
    }

    /**
     * ЧЕРВОНІТИ ВМІЄ — доведено тут, а не обіцяно в коміті.
     *
     * Тест вище зелений і на дереві, де розбір зламаний: порожній результат
     * однаково виглядає як «витоків нема» і як «сторож осліп». Сюди подані
     * рівно ті форми, які реально стояли в дереві до 20.09 (і форми-пастки,
     * які колись спробують обійти перейменуванням), плюс ті, що мають
     * лишитись мовчазними.
     */
    @Test
    fun `розбір ловить кожну форму, якою координати текли, і не чіпає налаштовану основу`() {
        val leaksWithHost = listOf(
            "https://photon.komoot.io/reverse?lat=\$lat&lon=\$lon&lang=\$photonLang",
            "https://nominatim.openstreetmap.org/reverse?lat=\$lat&lon=\$lon",
            // Перейменований хост — слово «komoot» тут не звучить узагалі.
            "https://geo.example-vendor.net/r?lat=\$lat&lon=\$lon",
            "https://api.open-meteo.com/v1/forecast?latitude=\$a&longitude=\$b",
            "https://api.adsb.lol/v2/lat/\$lat/lon/\$lon/dist/\$nm",
            "https://maps.google.com/?q=\$lat,\$lon",
        )
        for (u in leaksWithHost) {
            assertTrue("не побачив координати в «$u»", carriesCoord(u))
            assertTrue("не побачив зашитого зовнішнього хоста в «$u»", externalHostOrNull(u) != null)
        }

        // Координата в ШЛЯХУ — форма OSRM. Хоста в цих шматках немає взагалі:
        // їх ловить гілка 4, тут перевіряється лише впізнання форми.
        val coordsInPath = listOf(
            "\$fromLon,\$fromLat;\$toLon,\$toLat?overview=full&geometries=geojson",
            "\$lon,\$lat",
            ";\$lon,\$lat",
            "\${p.lon},\${p.lat}",
        )
        for (u in coordsInPath) {
            assertTrue("не побачив координати в ШЛЯХУ «$u»", carriesCoord(u))
        }

        val coordsInQuery = listOf(
            "node(around:\$radiusM,\$lat,\$lon);",
            "way[\"building\"](around:25,\$lat,\$lon);",
            "&gscoord=\$lat|\$lon&gslimit=5",
            "fromPlace=\${String.format(Locale.US, ",
        )
        for (u in coordsInQuery) {
            assertTrue("не побачив координати в запиті «$u»", carriesCoord(u))
        }
        assertTrue("радіус без точки — не координата", !carriesCoord("node(around:\$radiusM);"))

        // Основа з конфігурації: координата є, зашитого хоста немає — мовчить.
        val configured = "\$base/reverse?lat=\$lat&lon=\$lon&format=json"
        assertTrue("шаблон із конфігурації має нести координату", carriesCoord(configured))
        assertEquals(
            "шаблон із конфігурації не має зашитого хоста — інакше сторож червонив би " +
                "кожен правильний виклик і його б вимкнули",
            null,
            externalHostOrNull(configured),
        )

        // Свій сервер у локальній мережі — теж не «чужий бік».
        assertEquals(null, externalHostOrNull("http://192.168.1.10:8088/reverse?lat=\$lat&lon=\$lon"))

        // Не координата: шлях із версією та назвою профілю нікого не видає.
        assertTrue("шлях профілю — не координата", !carriesCoord("\$base/route/v1/\${profile.slug}/"))
        assertTrue("шлях моделі — не координата", !carriesCoord("/v1/models/\$name"))
    }

    /**
     * ТРИ КРОКИ НАД ПІДРОБЛЕНИМ ДЕРЕВОМ — та сама послідовність, якою фронт
     * 20.09 доводив гілку 4 на справжньому дереві:
     *  (а) постачальник підставляє публічний OSRM → червоно;
     *  (б) той самий хід, але хост і константа перейменовані так, що
     *      «openstreetmap» не звучить ніде → ЛИШАЄТЬСЯ червоно;
     *  (в) підстановку прибрано → зелено.
     */
    @Test
    fun `постачальник основи з зашитим чужим хостом червонить споживача координат`() {
        val endpointsPath = "core-net/src/main/java/p/GeoEndpoints.kt"
        val consumerPath = "core-net/src/main/java/p/OsrmClient.kt"

        fun endpoints(fallbackConst: String?): String = buildString {
            appendLine("package p")
            appendLine("fun Cfg.routingBasesFor(profileHost: String): List<String> {")
            appendLine("    val t = routingTemplate ?: return emptyList()")
            if (fallbackConst != null) appendLine("    return listOf(t, $fallbackConst)")
            appendLine("    return listOf(t)")
            appendLine("}")
            appendLine("fun Cfg.routingBaseFor(profileHost: String): String? =")
            appendLine("    routingBasesFor(profileHost).firstOrNull()")
        }

        // Споживач: координати в ШЛЯХУ, основа — з циклу над постачальником.
        // Зовнішнього хоста тут немає й близько; саме так виглядав OsrmClient.
        val consumer = """
            package p
            class OsrmClient {
                suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
                    for (base in cfg.routingBasesFor(profile.host)) {
                        val url = "${'$'}base/route/v1/${'$'}{profile.slug}/" +
                            "${'$'}fromLon,${'$'}fromLat;${'$'}toLon,${'$'}toLat?overview=full"
                        get(url)
                    }
                }
            }
        """.trimIndent()

        // (а) публічний OSRM позаду налаштованої основи.
        val a = scan(
            mapOf(
                endpointsPath to endpoints("PUBLIC_OSRM_TEMPLATE") +
                    "\nconst val PUBLIC_OSRM_TEMPLATE: String = \"https://routing.openstreetmap.de/{profile}\"\n",
                consumerPath to consumer,
            ),
        )
        assertTrue(
            "(а) публічний OSRM позаду основи має червонити споживача координат, знайдено: $a",
            a.keys.any { it.startsWith("$consumerPath:") },
        )
        assertTrue(
            "(а) у виправданні має стояти сам хост, а не здогад: $a",
            a.values.any { "routing.openstreetmap.de" in it },
        )

        // (б) той самий хід, інша назва хоста й константи.
        val b = scan(
            mapOf(
                endpointsPath to endpoints("VENDOR_ROUTER") +
                    "\nconst val VENDOR_ROUTER: String = \"https://geo.example-vendor.net/{profile}\"\n",
                consumerPath to consumer,
            ),
        )
        assertTrue(
            "(б) перейменування хоста НЕ має рятувати — ловиться форма, не слово. Знайдено: $b",
            b.keys.any { it.startsWith("$consumerPath:") },
        )

        // (в) підстановки немає — лишається сама налаштована основа.
        assertEquals(
            "(в) без зашитої основи сторож має мовчати, інакше він червонить кожен " +
                "правильний виклик і його вимкнуть",
            emptyMap<String, String>(),
            scan(mapOf(endpointsPath to endpoints(null), consumerPath to consumer)),
        )
    }

    @Test
    fun `основа зі списку дзеркал, параметра за замовчуванням чи склеєного рядка червонить, а збіг імені URL — ні`() {
        val mirrors = "core-net/src/main/java/p/Mirrors.kt" to """
            package p
            object Mirrors {
                val ENDPOINTS: List<String> = listOf(
                    "https://overpass.vendor.net/api/interpreter",
                )
            }
        """.trimIndent()
        val overpass = "core-net/src/main/java/p/Dossier.kt" to """
            package p
            class Dossier(private val wikiUrl: String = "https://wiki.vendor.net/w/api.php") {
                private val endpoints = Mirrors.ENDPOINTS
                suspend fun around(lat: Double, lon: Double) = raw("node(around:60,${'$'}lat,${'$'}lon);")
                suspend fun wiki(lat: Double, lon: Double) = get(
                    "${'$'}wikiUrl?action=query&list=geosearch" +
                        "&gscoord=${'$'}lat|${'$'}lon&gslimit=5",
                )
                private suspend fun raw(q: String): String? {
                    for (mirror in endpoints) get("${'$'}mirror?data=${'$'}q")
                    return null
                }
            }
        """.trimIndent()
        val planner = "core-net/src/main/java/p/Planner.kt" to """
            package p
            class Planner(private val baseUrl: String = DEFAULT) {
                fun plan(a: Double, b: Double) {
                    val q = listOf("fromPlace=${'$'}{fmt(a, b)}").joinToString("&")
                    get("${'$'}baseUrl/api/v1/plan?${'$'}q")
                }
                companion object {
                    const val DEFAULT: String = "https://transit.vendor.net"
                }
            }
        """.trimIndent()
        val found = scan(mapOf(mirrors, overpass, planner))
        for (host in listOf("overpass.vendor.net", "wiki.vendor.net", "transit.vendor.net")) {
            assertTrue("не побачив $host: $found", found.values.any { host in it })
        }

        val collision = scan(
            mapOf(
                "app/src/main/java/p/Delete.kt" to
                    "package p\nobject Delete {\n    const val URL = \"https://site.vendor.net/delete\"\n}\n",
                "feature-stream/src/main/java/p/Feeds.kt" to
                    "package p\ninternal fun httpGet(url: String): String? {\n    return URL(url).readText()\n}\n",
                "core-net/src/main/java/p/Local.kt" to
                    "package p\nclass Local(private val base: String) {\n" +
                    "    fun r(lat: Double, lon: Double) = httpGet(\"${'$'}base/reverse?lat=${'$'}lat&lon=${'$'}lon\")\n" +
                    "    private fun httpGet(u: String): String? = null\n}\n",
            ),
        )
        assertEquals("конструктор URL(…) — не посилання на константу URL", emptyMap<String, String>(), collision)
    }

    @Test
    fun `тіло POST із координатами на зашитий хост червоніє, а свій сервер і збіг імен — ні`() {
        val geo = "core-symbiote/src/main/java/p/Geo.kt" to """
            package p
            class Nearby(private val http: Http, private val endpoint: String = Nearby.OVERPASS) {
                suspend fun run(lat: Double, lon: Double, r: Int) {
                    val around = "around:${'$'}r,${'$'}{c(lat)},${'$'}{c(lon)}"
                    val ql = "[out:json];(" +
                        "node[\"amenity\"](${'$'}around);" +
                        ");out;"
                    val body = http.postText(endpoint, "data=" + enc(ql))
                }
                companion object {
                    const val OVERPASS = "https://overpass.vendor.net/api/interpreter"
                }
            }
        """.trimIndent()
        val json = "core-net/src/main/java/p/Report.kt" to """
            package p
            class Report(private val client: Client) {
                suspend fun send(lat: Double, lon: Double) {
                    client.post("https://report.vendor.net/v1/points") {
                        setBody(buildJsonObject { put("lat", lat); put("lon", lon) })
                    }
                }
            }
        """.trimIndent()
        val own = "core-net/src/main/java/p/Own.kt" to """
            package p
            class Own(private val client: Client, private val baseUrl: String) {
                suspend fun send(lat: Double, lon: Double) {
                    client.post("${'$'}baseUrl/v1/points") {
                        setBody(buildJsonObject { put("lat", lat); put("lon", lon) })
                    }
                }
            }
        """.trimIndent()
        val found = scan(mapOf(geo, json, own))
        assertTrue("не побачив POST в Overpass: $found", found.any { it.key.startsWith(geo.first) && "overpass.vendor.net" in it.value })
        assertTrue("не побачив JSON-тіло: $found", found.any { it.key.startsWith(json.first) && "report.vendor.net" in it.value })
        assertTrue("свій сервер із конфігурації — не витік: $found", found.keys.none { it.startsWith(own.first) })

        val noCoords = scan(mapOf(geo.first to geo.second.replace("around:", "radius:")))
        assertEquals("без координат у тілі POST мовчить", emptyMap<String, String>(), noCoords)

        val sameName = scan(
            mapOf(
                "core-net/src/main/java/p/A.kt" to
                    "package p\nobject A {\n    const val ENDPOINT = \"https://a.vendor.net/api\"\n}\n",
                "core-net/src/main/java/p/B.kt" to
                    "package p\nobject B {\n    const val ENDPOINT = \"https://b.vendor.net/api\"\n}\n",
                "core-net/src/main/java/p/C.kt" to
                    "package p\nclass C(private val endpoint: String = B.ENDPOINT) {\n" +
                    "    fun go(lat: Double, lon: Double) = http.postText(endpoint, \"q=around:5,${'$'}lat,${'$'}lon\")\n}\n",
            ),
        )
        assertTrue("кваліфіковане імʼя бере хост свого власника: $sameName", sameName.values.any { "b.vendor.net" in it && "a.vendor.net" !in it })
    }

    @Test
    fun `на справжньому дереві сторож бачить POST GeoTool в Overpass`() {
        val raw = scan(treeSources(), honourAllowed = false)
        assertTrue(
            "GeoTool шле координати тілом POST в Overpass, а сторож цього не бачить: ${raw.keys}",
            raw.any {
                it.key.startsWith("core-symbiote/src/main/java/local/phantom/companion/core/tool/impl/GeoTool.kt:") &&
                    "overpass-api.de" in it.value
            },
        )
    }
}
