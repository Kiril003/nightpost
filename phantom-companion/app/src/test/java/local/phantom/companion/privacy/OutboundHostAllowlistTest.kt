package local.phantom.companion.privacy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.AiBootstrapper
import local.phantom.companion.core.ai.BrainState
import local.phantom.companion.core.ai.ModelManager
import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.core.ai.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * СТОРОЖ ВИХІДНИХ ЗВЕРНЕНЬ.
 *
 * 05.09 вимір (phantom-phone-downloads-without-a-key): на холодному старті, без
 * жодного ключа й дотику, застосунок тягнув ~398 МБ з huggingface.co
 * (SUPERTONIC voice, [local.phantom.companion.data.LiveVoiceProvisioner]) —
 * сайт це вже каже, застосунок мовчав. Полагоджено того самого дня: сповіщення
 * після факту замінено на [local.phantom.companion.data.LiveVoiceState.NotRequested]
 * — жоден рядок нижче більше не носить умову «СТАРТ БЕЗ КЛЮЧА», і другий тест
 * тут стереже саме це. Один переліт коду не лишається доказом назавжди:
 * наступний PR може додати новий `https://` літерал у бойовий код, і ніхто про
 * це не дізнається, поки хтось не перевимірить трафік руками.
 *
 * Правило: у `src/main` кожного модуля нема жодного хоста поза цим переліком.
 * Кожен елемент — з причиною і УМОВОЮ спрацювання (старт / дія людини / ключ),
 * так само як таблиця в phantom-phone-downloads-without-a-key. Новий хост
 * ламає тест — це навмисно: додавання ще одного вузла, куди щось летить без
 * ключа, має пройти через людину, яка веде цей перелік, а не крізь review,
 * що не знав, куди дивитись.
 */
class OutboundHostAllowlistTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    // Читається з settings.gradle.kts: рукописний перелік пропустив :core-truth,
    // щойно той зʼявився 24.09.
    private val modules: List<String> by lazy {
        val found = Regex("""include\(\s*"(:[A-Za-z0-9_:-]+)"\s*\)""")
            .findAll(File(repoRoot(), "settings.gradle.kts").readText())
            .map { it.groupValues[1].removePrefix(":").replace(':', '/') }
            .toList()
        check(found.size >= 20 && "app" in found) { "у settings.gradle.kts знайдено модулів: $found" }
        found
    }

    /**
     * Хост -> причина й умова. Умова — one of:
     *   СТАРТ БЕЗ КЛЮЧА  — реально тягне мережу при холодному старті, без дії
     *                       людини й без ключа (те, що шукає це завдання);
     *   ДІЯ ЛЮДИНИ        — лише після дотику / відкритого екрана / запиту;
     *   КЛЮЧ              — лише коли є ключ (хмарний ШІ, платний тайл-провайдер);
     *   ФОНОВЕ ОБІЦЯНЕ    — періодичний фон, але саме та категорія («тривоги»,
     *                       «пакети даних»), яку сайт продає окремо;
     *   НЕ МЕРЕЖА         — рядок є, виклику нема (коментар, приклад, простір
     *                       імен, локальна адреса, мертвий код).
     */
    private val allowedHosts: Map<String, String> = mapOf(
        // ── СТАРТ БЕЗ КЛЮЧА — саме те, що ця хвиля перевіряла ──────────────
        "huggingface.co" to
            "ДІЯ ЛЮДИНИ — усі моделі, включно з SUPERTONIC (ModelManager, ~398 МБ). " +
                "До 05.09 LiveVoiceProvisioner.arm() вирішував Fetch на самому " +
                "Wi-Fi/диску, без дотику й без ключа; decideLiveVoice тепер бере " +
                "voicePrefs.liveVoiceRequested першою умовою (LiveVoiceState.NotRequested, " +
                "VoiceProvision.kt) — той самий дотик, що й YAMNET/EMBEDDING " +
                "(кнопка ⭳ у Налаштуваннях). Моделі мови телефон не тягне взагалі.",
        "alphacephei.com" to
            "ДІЯ ЛЮДИНИ — VOSK (137 МіБ), тягне лише HearingProvisioner.ensureHearing() " +
                "після першого дотику до мікрофона; мовчить на холодному старті.",

        // ── КЛЮЧ ────────────────────────────────────────────────────────
        "generativelanguage.googleapis.com" to
            "КЛЮЧ — GeminiCloudRoute.streamOnce() виходить одразу, без жодного " +
                "запиту, якщо apiKeyProvider() == null.",
        "api.maptiler.com" to "КЛЮЧ — потрібен ВЛАСНИЙ ключ тайл-провайдера в URL.",
        "tiles.stadiamaps.com" to "КЛЮЧ — потрібен ВЛАСНИЙ ключ тайл-провайдера в URL.",

        // ── ДІЯ ЛЮДИНИ — інструмент асистента або відкритий екран мапи ─────
        "api.devnet.solana.com" to
            "ДІЯ ЛЮДИНИ або ВХІДНИЙ ПЕРЕКАЗ — публічний вузол Solana (тестова мережа), якщо в local.properties " +
                "не задано свого. Баланс, переказ, перевірка картки переказу/готівки/відбитка; адреса гаманця, не особа.",
        "api.mainnet-beta.solana.com" to
            "ДІЯ ЛЮДИНИ — той самий вузол основної мережі; перевірка Seeker завжди тут (SGT є лише в mainnet).",
        "phantom-os.dev" to
            "НЕ МЕРЕЖА з телефона — ідентичність застосунку для чужого гаманця (MWA); її може відкрити гаманець, не ми.",
        "api.open-meteo.com" to "ДІЯ ЛЮДИНИ — WeatherTool, анонімно, лише коли асистент просить погоду.",
        "nominatim.openstreetmap.org" to "НЕ МЕРЕЖА — лише GeoEndpointsConfig.devFallback для тестів; публічного тіру пошуку немає (R0-01).",
        "tile.openstreetmap.org" to "ДІЯ ЛЮДИНИ — запасні тайли мапи, лише коли мапа відкрита.",
        "routing.openstreetmap.de" to "ДІЯ ЛЮДИНИ — запасний маршрутизатор.",
        "overpass-api.de" to "ДІЯ ЛЮДИНИ — запит POI/обʼєктів на відкритій мапі.",
        "lz4.overpass-api.de" to "ДІЯ ЛЮДИНИ — дзеркало overpass-api.de.",
        "z.overpass-api.de" to "ДІЯ ЛЮДИНИ — дзеркало overpass-api.de.",
        "overpass.private.coffee" to "ДІЯ ЛЮДИНИ — дзеркало overpass-api.de.",
        "overpass.kumi.systems" to "ДІЯ ЛЮДИНИ — дзеркало overpass-api.de.",
        "uk.wikipedia.org" to "ДІЯ ЛЮДИНИ — досьє місця, відкрите оператором на мапі.",
        "www.wikidata.org" to "ДІЯ ЛЮДИНИ — досьє місця.",
        "api.waqi.info" to "ДІЯ ЛЮДИНИ — шар якості повітря, вмикається оператором.",
        "firms.modaps.eosdis.nasa.gov" to "ДІЯ ЛЮДИНИ — шар пожеж, вмикається оператором.",
        "api.open-elevation.com" to "ДІЯ ЛЮДИНИ — висота для профілю маршруту.",
        "api.adsb.lol" to "ДІЯ ЛЮДИНИ — інструмент відстеження літаків.",
        "lite.duckduckgo.com" to "ДІЯ ЛЮДИНИ — WebSearchTool, лише коли асистент шукає в мережі.",
        "transit.land" to "ДІЯ ЛЮДИНИ — розклад транспорту за запитом.",
        "api.transitous.org" to
            "ДІЯ ЛЮДИНИ — відкритий всесвітній розклад і маршрутизація транспорту " +
                "(MOTIS) за запитом на мапі. З 20.09 сюди ж іде ВИДИМА РАМКА шару " +
                "зупинок (/api/v1/map/stops?min=…&max=…), і це ширше за тап: рамка " +
                "летить щоразу, коли камера стала на новій клітинці, поки мапа " +
                "відкрита. Дозволено ЯВНО, бо без цього шар зупинок порожній за " +
                "побудовою — пакет транспорту у світі існує рівно один (Люксембург), " +
                "а даних по Львову 769 зупинок і по Києву 527 (заміряно 20.09). " +
                "Межі: запит анонімний (ні ключа, ні ідентифікатора пристрою), тіло — " +
                "публічний перелік зупинок; рамка — НЕ позиція оператора, а те, куди " +
                "дивиться камера; і запит не робиться взагалі, коли офлайн-пакет цю " +
                "рамку вже накрив (MapPackTransit.onCamera виходить раніше). Той " +
                "самий обсяг, що вже йде на overpass-api.de для POI відкритої мапи.",
        "track.ua-gis.com" to "ДІЯ ЛЮДИНИ — живі позиції транспорту Львова, відкритий шар.",
        "193.23.225.211" to
            "ДІЯ ЛЮДИНИ — опублікований GTFS-розклад Києва (Київпастранс/КМДА, " +
                "UkrainePack.scheduleFeeds), фетчиться лише коли відкрито транспортну " +
                "картку Києва на мапі. HTTP навмисно: HTTPS на цій адресі не відповідає " +
                "взагалі, а офіційне https-посилання data.gov.ua саме́ 302-редіректить " +
                "сюди — виняток у network_security_config.xml звужено до цієї адреси й " +
                "нічого більше (див. UkrainePack.kt KDoc). Загроза — підміна чисел у " +
                "дорозі (цілісність), не розкриття: запит анонімний, тіло — публічний " +
                "розклад.",
        "api.golemio.cz" to "ДІЯ ЛЮДИНИ — живі позиції транспорту Праги (EuropePack), відкритий GTFS-RT.",

        // ── ДІЯ ЛЮДИНИ — EuropePack.transitFeeds / scheduleFeeds, 14.09 ────
        //
        // Стара Прага (api.golemio.cz/v2/public/…) і www.wroclaw.pl перевірені
        // curl 14.09 і виявились мертвими (401, 403) — Вроцлав знято без заміни,
        // запис www.wroclaw.pl прибрано звідси разом із ним. Решта нижче — 30
        // живих (transitFeeds) і 12 статичних (scheduleFeeds) хостів з каталогу
        // MobilityData (authentication_type=0, без ключа), КОЖЕН продзвонений
        // curl 14.09: HTTP 200, тіло розбирається як GTFS-RT FeedMessage або
        // zip. Усі — тим самим правилом, що Прага/Львів/Київ: фетчиться лише
        // коли відкрито транспортну картку конкретного міста на мапі.
        "gtfs.sofiatraffic.bg" to "ДІЯ ЛЮДИНИ — живі позиції й розклад Софії (EuropePack).",
        "jbb.ghsq.de" to "ДІЯ ЛЮДИНИ — живі позиції Elron, нацзалізниці Естонії (EuropePack); неофіційний фід Transitous.",
        "gtfs.emtmalaga.es" to "ДІЯ ЛЮДИНИ — живі позиції й розклад Малаги (EuropePack).",
        "api.control.optibus.co" to "ДІЯ ЛЮДИНИ — живі позиції й розклад Фуенлабради/Мадрид (EuropePack).",
        "enzeyiwpoomhlxmcjivn.supabase.co" to "ДІЯ ЛЮДИНИ — живі позиції ArroyoBus, Вальядолід (EuropePack).",
        "ctb-gtfs-rt.s3.eu-south-2.amazonaws.com" to "ДІЯ ЛЮДИНИ — живі позиції Metro Більбао (EuropePack).",
        "gtfsrt.renfe.com" to "ДІЯ ЛЮДИНИ — живі позиції Renfe Cercanías, нацмережа приміських потягів Іспанії (EuropePack).",
        "opendata.euskadi.eus" to "ДІЯ ЛЮДИНИ — живі позиції dBus, Сан-Себастьян (EuropePack).",
        "realtime.hsl.fi" to "ДІЯ ЛЮДИНИ — живі позиції HSL, Гельсінкі (EuropePack).",
        "bdx.mecatran.com" to
            "ДІЯ ЛЮДИНИ — живі позиції й розклад TBM, Бордо (EuropePack). apiKey у " +
                "URL — публічний відкритий ключ каталогу, не секрет власника.",
        "185.220.72.38" to
            "ДІЯ ЛЮДИНИ — живі позиції Tango Bus, Нім (EuropePack). HTTP навмисно " +
                "(голий IP, TLS немає) — виняток у network_security_config.xml звужено " +
                "до цієї адреси й нічого більше.",
        "gtfs.gpspositions.net" to "ДІЯ ЛЮДИНИ — живі позиції автобуса Орошгаза, Угорщина (EuropePack).",
        "tpl.actv.it" to "ДІЯ ЛЮДИНИ — живі позиції ACTV (судна й автобуси), Венеція (EuropePack).",
        "romamobilita.it" to "ДІЯ ЛЮДИНИ — живі позиції й розклад Roma Mobilità, Рим (EuropePack).",
        "proxy.busone.app" to "ДІЯ ЛЮДИНИ — живі позиції Cotral, регіон Lazio (EuropePack).",
        "s3.eu-south-1.amazonaws.com" to "ДІЯ ЛЮДИНИ — живі позиції TEP, Парма (EuropePack).",
        "www.stops.lt" to
            "ДІЯ ЛЮДИНИ — живі позиції й розклад Вільнюса (EuropePack). Розклад — " +
                "HTTP навмисно (джерело не відповідає по HTTPS); виняток у " +
                "network_security_config.xml звужено до цієї адреси.",
        "gtfs.ovapi.nl" to
            "ДІЯ ЛЮДИНИ — живі позиції OVapi, нацагрегат Нідерландів (EuropePack). HTTP " +
                "навмисно (джерело не відповідає по HTTPS); виняток у " +
                "network_security_config.xml звужено до цієї адреси.",
        "api.entur.io" to "ДІЯ ЛЮДИНИ — живі позиції Entur, нацагрегат Норвегії (EuropePack).",
        "gtfs.ztp.krakow.pl" to "ДІЯ ЛЮДИНИ — живі позиції й розклад ZTP Кракова (EuropePack).",
        "www.zditm.szczecin.pl" to "ДІЯ ЛЮДИНИ — живі позиції й розклад ZDiTM Щецина (EuropePack).",
        "www.mpkrzeszow.pl" to "ДІЯ ЛЮДИНИ — живі позиції MPK і PKS Жешува (EuropePack).",
        "api.carrismetropolitana.pt" to "ДІЯ ЛЮДИНИ — живі позиції Carris Metropolitana, регіон Лісабона (EuropePack).",
        "data.pid.cz" to
            "ДІЯ ЛЮДИНИ — розклад PID, Прага (EuropePack.scheduleFeeds). HTTP навмисно " +
                "(офіційне джерело не відповідає по HTTPS); виняток у " +
                "network_security_config.xml звужено до цієї адреси.",
        "dev.hsl.fi" to
            "ДІЯ ЛЮДИНИ — розклад HSL, Гельсінкі (EuropePack.scheduleFeeds). HTTP навмисно " +
                "(офіційне джерело не відповідає по HTTPS); виняток у " +
                "network_security_config.xml звужено до цієї адреси.",
        "actv.avmspa.it" to
            "ДІЯ ЛЮДИНИ — розклад ACTV (судна), Венеція (EuropePack.scheduleFeeds). HTTP " +
                "навмисно (офіційне джерело не відповідає по HTTPS); виняток у " +
                "network_security_config.xml звужено до цієї адреси.",
        "eu-gtfs.remix.com" to "ДІЯ ЛЮДИНИ — розклад Elron, нацзалізниці Естонії (EuropePack.scheduleFeeds); конверсія Transitous.",
        "tiles.openfreemap.org" to
            "ДІЯ ЛЮДИНИ — типовий стиль векторних тайлів на відкритій мапі " +
                "(і той самий хост у tools/obsidian — окремий інструмент збірки " +
                "офлайн-пакетів на машині розробника, у телефон не їде).",
        "s3.amazonaws.com" to "ДІЯ ЛЮДИНИ — рельєфні тайли (elevation-tiles-prod), відкритий шар.",
        "api.rainviewer.com" to "ДІЯ ЛЮДИНИ — шар радару опадів, відкритий оператором.",
        "www.openstreetmap.org" to
            "НЕ МЕРЕЖА ЗАСТОСУНКУ — посилання «відкрити в OSM», іде в зовнішній " +
                "браузер через Intent, застосунок сам його не запитує.",
        "maps.google.com" to
            "НЕ МЕРЕЖА ЗАСТОСУНКУ — текст «моє авто тут: …», що людина сама " +
                "надсилає (CarCockpitSheet); застосунок цей URL не запитує.",
        "wiki.openstreetmap.org" to
            "НЕ МЕРЕЖА — посилання-довідка в KDoc (RouteTileMath), не виклик.",
        "try.phantom-os.dev" to
            "ДІЯ ЛЮДИНИ — сигналінг дзвінків 1:1 та ефемерні ключі Coturn ретранслятора.",

        // ── ФОНОВЕ ОБІЦЯНЕ — саме категорія «тривоги» / «пакети даних» з сайту ──
        "ubilling.net.ua" to "ФОНОВЕ ОБІЦЯНЕ — стрічка повітряних тривог (UkrainePack.alarmFeeds).",
        "siren.pp.ua" to "ФОНОВЕ ОБІЦЯНЕ — стрічка повітряних тривог.",
        "api.alerts.in.ua" to "ФОНОВЕ ОБІЦЯНЕ — стрічка повітряних тривог (опційний токен).",

        // ── НЕ МЕРЕЖА — рядок є, живого виклику нема ───────────────────────
        "ipapi.co" to "НЕ МЕРЕЖА — IpApiClient визначений, але ніде в src/main не створюється (мертвий код).",
        "127.0.0.1" to "НЕ МЕРЕЖА — префікс локальної адреси в OsrmClient (пошук OSRM у ЛОКАЛЬНІЙ мережі).",
        "10." to "НЕ МЕРЕЖА — префікс приватної підмережі в OsrmClient.",
        "172." to "НЕ МЕРЕЖА — префікс приватної підмережі в OsrmClient.",
        "192.168." to "НЕ МЕРЕЖА — префікс приватної підмережі в OsrmClient.",
        "localhost" to "НЕ МЕРЕЖА — префікс локальної адреси в OsrmClient.",
        "phantom.local" to "НЕ МЕРЕЖА — синтетичний origin вбудованого WebView веб-месенджера, DNS не резолвиться.",
        "example.invalid" to "НЕ МЕРЕЖА — приклад посилання (RFC 2606) у системному промпті ШІ, ніколи не запитується.",
        "relay.example" to "НЕ МЕРЕЖА — приклад значення в KDoc GeoEndpointsConfig; типовий конфіг DISABLED.",
        "x.com." to "НЕ МЕРЕЖА — приклад речення в KDoc MarkdownWidget.urlEnd, не виклик.",
        "www.topografix.com" to "НЕ МЕРЕЖА — простір імен XML у GPX-експорті (TrackExporter), не мережевий запит.",
    )

    private val hostLiteral = Regex("""https?://[A-Za-z0-9._%-]+""")

    private fun kotlinFiles(): List<File> {
        val root = repoRoot()
        val files = modules.flatMap { m ->
            File(root, "$m/src/main").let { dir ->
                if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                else emptyList()
            }
        }
        // Якщо модулів стало 0 файлів — дерево переїхало, і зелений результат
        // нижче нічого не важить.
        check(files.size > 100) { "знайдено підозріло мало .kt файлів у src/main (${files.size})" }
        return files
    }

    @Test
    fun `у бойовому коді нема мережевого хоста поза переліком дозволених`() {
        val found = sortedMapOf<String, MutableList<String>>()
        kotlinFiles().forEach { f ->
            hostLiteral.findAll(f.readText()).forEach { m ->
                val host = m.value.removePrefix("https://").removePrefix("http://")
                if (host !in allowedHosts) {
                    found.getOrPut(host) { mutableListOf() }.add(f.path.removePrefix(repoRoot().path + "/"))
                }
            }
        }
        assertEquals(
            "новий хост поза переліком дозволених (${found.size}) — додай його в " +
                "allowedHosts З ПРИЧИНОЮ І УМОВОЮ (старт без ключа / дія людини / ключ / " +
                "фонове обіцяне / не мережа), не просто прибери цей рядок:\n    " +
                found.entries.joinToString("\n    ") { (host, files) -> "$host  — ${files.first()}" },
            emptyMap<String, List<String>>(),
            found,
        )
    }

    // `https?://` вище не бачить схем ICE. 0feee5bd зняв stun.l.google.com з
    // iceConfig: кожен дзвінок віддавав Google адресу людини. Свій STUN/TURN
    // приходить лише з конфігу ретранслятора (`stun:$host`), тож будь-який
    // вписаний літерал-хост — чужий. Лапка перед схемою обовʼязкова: без неї
    // ловляться `turn:lanes` (ключ OSM) і `{turn:t.turn}` у мініфікованому JS.
    private val iceUri =
        Regex("""["'`](stuns?|turns?):(?://)?(\[[0-9A-Fa-f:.]+]|[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""")

    private fun iceSources(): List<File> {
        val root = repoRoot()
        val code = modules.flatMap { m ->
            File(root, "$m/src").takeIf { it.isDirectory }?.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
                .orEmpty()
                .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml", "json") } }
        }
        val web = File(root, "app/src/main/assets/webmsgr").walkTopDown()
            .filter { it.isFile && it.extension in setOf("js", "html", "json") }.toList()
        check(web.size > 5) { "бандла веб-месенджера нема — половина сторожа сліпа" }
        return code + web
    }

    @Test
    fun `дзвінок не стукає до чужого STUN чи TURN`() {
        listOf(
            "\"stun:stun.l.google.com:19302\"" to true,
            "urls:['turns:turn.example.org:5349']" to true,
            "`stun:[2001:db8::1]:3478`" to true,
            "\"stun:\$host:\$port\"" to false,
            "\"turn:lanes:forward\"" to false,
            "{turn:t.turn,ttl:0}" to false,
        ).forEach { (s, foreign) ->
            assertEquals("сторож ICE помиляється на $s", foreign, iceUri.containsMatchIn(s))
        }

        val found = iceSources().flatMap { f ->
            iceUri.findAll(f.readText()).map { "${it.value.drop(1)}  — ${f.path.removePrefix(repoRoot().path + "/")}" }.toList()
        }
        assertEquals(
            "у коді зʼявився вписаний STUN/TURN (${found.size}). Він бачить адресу людини й " +
                "мить кожного дзвінка. Свій ретранслятор бере хост лише з relayConfig():\n    " +
                found.joinToString("\n    "),
            emptyList<String>(),
            found,
        )
    }

    /**
     * Резолюція цієї хвилі як інваріант, не як текст у коміті. Категорія
     * «СТАРТ БЕЗ КЛЮЧА» існувала для huggingface.co до того, як
     * [local.phantom.companion.data.LiveVoiceState.NotRequested] зробив
     * дотик оператора першою умовою. Хтось, хто повертає нецензуроване
     * авто-качання, має свідомо повернути й цей рядок причини — а не
     * лишити стару, вже неправдиву позначку «ДІЯ ЛЮДИНИ» на новому коді.
     */
    @Test
    fun `жоден хост не носить позначку «старт без ключа» — резолюцію цієї хвилі неможливо тихо відкотити`() {
        val regressed = allowedHosts.filterValues { it.startsWith("СТАРТ БЕЗ КЛЮЧА") }
        assertEquals(
            "хост(и) знову позначені як старт без ключа й дії людини (${regressed.size}) — " +
                "перечитай phantom-phone-downloads-without-a-key і LiveVoiceState.NotRequested " +
                "перш ніж лишати цю позначку:\n    " + regressed.keys.joinToString("\n    "),
            emptyMap<String, String>(),
            regressed,
        )
    }

    /** Розуму на телефоні більше немає: навіть «людина чекає» не тягне жодного байта моделі. */
    @Test
    fun `розум не качається ні на холодному старті, ні на дотик`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val boot = AiBootstrapper(
            router = object : AIRouter {
                override fun complete(intent: AIIntent): Flow<AIChunk> = emptyFlow()
                override suspend fun availableRoutes(): Set<Route> = emptySet()
            },
            scope = scope,
        )
        assertEquals(BrainState.Idle, boot.ensureBrainNow(urgent = false))
        assertEquals(BrainState.Idle, boot.ensureBrainNow(urgent = true))
        assertTrue(ModelType.values().none { it.fileName.endsWith(".task") })
        scope.cancel()
    }
}
