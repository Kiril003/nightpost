package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ЧОТИРИ ЗАКОНИ «ДІЛИТИСЯ ПОЛОЖЕННЯМ», ЯКИХ КОМПІЛЯТОР НЕ БАЧИТЬ.
 *
 * **№1 — чуже положення береться ЛИШЕ з листа.** Не з реєстру, не з маячка, не
 * з «востаннє бачили». Тієї миті, коли точку можна зібрати з присутності, у
 * застосунку зʼявляється шар присутності — навіть якщо його ніхто не хотів.
 *
 * **№2 — дія є там, де людина.** З картки контакта можна почати ділитись, а
 * доки триває — на СКЛІ мапи стоїть плашка. Не в шторці: шторку змахують.
 *
 * **№3 — правда про доставку НАПЕРЕД.** Обидві поверхні розрізняють усі чотири
 * вироки; «без ключа» не мовчить.
 *
 * **№4 — після строку не лишається сліду.** Не «не показується»: у журналі не
 * лишається рядка, з якого точку можна зібрати назад.
 *
 * GATE LAW: сторож читає ДЖЕРЕЛО файлом, повз up-to-date Gradle — на валі
 * запускати з `--rerun`.
 */
class PeerLiveReachFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-net/ not found") as Nothing
    }

    private fun read(path: String): String {
        val f = File(repoRoot(), path)
        assertTrue("сторож осліп: немає $path", f.isFile)
        return f.readText()
    }

    private fun sourcesUnder(vararg roots: String): List<File> = roots.flatMap { root ->
        File(repoRoot(), root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }
            .toList()
    }

    // ── №1 ───────────────────────────────────────────────────────────────

    /**
     * Хто взагалі має право зібрати чужу точку. Список навмисне короткий.
     *
     * Було двоє. `MessengerGeo.kt` пішов 06.09 разом із вузловим месенджером;
     * лишився один збирач, і це не послаблення — дозвіл, яким ніхто не
     * користується, з часом пускає когось нового мовчки.
     *
     * 24.09 — другий: `PeerWhereHub.kt`, «люди на мапі». Точку він складає з
     * кадру `PH-WHR:`, що прийшов листом від людини з реєстру, і ні з чого іншого.
     */
    private val allowedProducers = setOf("PeerLiveHub.kt", "PeerWhereHub.kt")

    @Test
    fun `a foreign position is built only from a letter, never from presence`() {
        val builders = sourcesUnder("app/src/main", "feature-stream/src/main")
            .filter { Regex("""\bPeerPoint\(""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toSet()
        assertEquals(
            "чужу точку збирає лише той, хто читає лист. Новий збирач: " +
                (builders - allowedProducers),
            emptySet<String>(),
            builders - allowedProducers,
        )
    }

    @Test
    fun `no producer derives a position from the roster or the beacon`() {
        // «Востаннє бачили» — це присутність. Точка, зібрана з неї, і є той шар
        // «хто де зараз», якого тут не буде.
        val forbidden = listOf("lastSeenMs", "isLive(", "PeerBeacon", "PeerAnnounce", "addressed")
        val offenders = sourcesUnder("app/src/main", "feature-stream/src/main")
            .filter { it.name in allowedProducers }
            .flatMap { f ->
                val body = f.readText()
                // Тіло функції, що будує точку, і тільки воно: вирок «чи дійде»
                // законно питає, чи телефон поруч, — але не малює цим точку.
                val where = body.indexOf("PeerPoint(")
                val window = body.substring(maxOf(0, where - 1_200), where)
                forbidden.filter { window.contains(it) }.map { "${f.name}: $it" }
            }
        assertEquals(offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun `nothing turns the whole roster into positions`() {
        // Найкоротший шлях до шару присутності — перебрати реєстр і намалювати
        // кожного. Такого перебору не має бути ніде.
        val offenders = sourcesUnder("app/src/main", "feature-stream/src/main").filter { f ->
            val body = f.readText()
            Regex("""peers\.value\.map\s*\{[^}]{0,400}PeerPoint\(""").containsMatchIn(body) ||
                Regex("""roster\(\)\.map\s*\{[^}]{0,400}PeerPoint\(""").containsMatchIn(body)
        }.map { it.name }
        assertEquals("шар присутності зібрано з реєстру: $offenders", emptyList<String>(), offenders)
    }

    // ── №2 ───────────────────────────────────────────────────────────────

    @Test
    fun `the contact card offers the action and the host wires it`() {
        val card = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/PeerCardScreen.kt",
        )
        assertTrue("з картки контакта немає дії «поділитися»", card.contains("PeerShareLocationCard("))
        val host = read("app/src/main/java/local/phantom/companion/navigation/PeerCardDestination.kt")
        assertTrue("дія намальована, але ні до чого не підʼєднана", host.contains("onShareStart ="))
        assertTrue(host.contains("container.liveShare.start("))
        assertTrue("немає чим зупинити", host.contains("container.liveShare.stop("))
    }

    @Test
    fun `the banner lives on the glass, not in the shade`() {
        val chrome = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapChrome.kt",
        )
        assertTrue("плашки сеансу немає на склі мапи", chrome.contains("PeerSharePill("))
        val shade = read("app/src/main/java/local/phantom/companion/service/StatusShade.kt")
        assertFalse("плашка сеансу переїхала в шторку — її змахують і забувають",
            shade.contains("PeerSharePill"))
        val host = read("app/src/main/java/local/phantom/companion/ui/home/MapTab.kt")
        assertTrue("плашка намальована, але сеансів не бачить", host.contains("liveShareBanners ="))
    }

    // ── №3 ───────────────────────────────────────────────────────────────

    @Test
    fun `both surfaces say all four verdicts, never only three`() {
        val verdicts = listOf("Reach.NO_KEY", "Reach.MESH", "Reach.RELAY", "Reach.QUEUED")
        listOf(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/PeerShareLocation.kt",
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/PeerSharePill.kt",
        ).forEach { path ->
            val body = read(path)
            val missing = verdicts.filterNot { body.contains(it) }
            assertEquals("$path мовчить про: $missing", emptyList<String>(), missing)
        }
    }

    @Test
    fun `both surfaces also say whether there is a position at all`() {
        // «Чи дійде» і «чи є що везти» — два різні питання, і друге мовчало:
        // сеанс у приміщенні або без дозволу доходить чудово й не везе нічого.
        val verdicts = listOf("Position.SEARCHING", "Position.NO_PERMISSION")
        listOf(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/PeerShareLocation.kt",
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/PeerSharePill.kt",
        ).forEach { path ->
            val body = read(path)
            val missing = verdicts.filterNot { body.contains(it) }
            assertEquals("$path мовчить про: $missing", emptyList<String>(), missing)
        }
        val strings = read("feature-stream/src/main/res/values-uk/strings.xml")
        val english = read("feature-stream/src/main/res/values/strings.xml")
        listOf(
            "peer_share_wait", "peer_share_pos_searching",
            "peer_share_pos_denied", "peer_share_pos_denied_tap", "peer_share_grant",
        ).forEach {
            assertTrue("немає українського рядка $it", strings.contains("\"$it\""))
            assertTrue("немає англійського рядка $it", english.contains("\"$it\""))
        }
    }

    @Test
    fun `a session with no position does not promise delivery`() {
        val pill = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/PeerSharePill.kt",
        )
        val card = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/PeerShareLocation.kt",
        )
        assertTrue("плашка каже «ділюсь» без жодного виміру", pill.contains("PeerLiveShare.promises("))
        assertTrue(
            "картка каже «ділюсь» без жодного виміру",
            card.contains("session.lastSentAtMs > 0L || position == PeerLiveShare.Position.HELD"),
        )
        assertTrue("без дозволу немає чим його дати", pill.contains("rememberLocationGrant("))
        assertTrue("без дозволу немає чим його дати", card.contains("rememberLocationGrant("))
    }

    @Test
    fun `the pill does not clip the sentence it exists to say`() {
        val pill = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/PeerSharePill.kt",
        )
        // «поруч не видно — потримає сховок, поки телефон увімкнеться» на
        // 390 px в один рядок обривається на третьому слові, і плашка каже
        // протилежне тому, що мала.
        assertFalse(
            "речення про шлях знову ріжеться в один рядок",
            Regex("""PhantomCaption\([^)]*maxLines = 1""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(pill),
        )
        // Найдовші фрази обох мов мусять лишатись двома рядками, не трьома.
        listOf(
            "feature-stream/src/main/res/values-uk/strings.xml",
            "feature-stream/src/main/res/values/strings.xml",
        ).forEach { path ->
            val body = read(path)
            val longest = Regex("""<string name="peer_share_(reach_\w+|pos_\w+)">([^<]*)</string>""")
                .findAll(body)
                .map { it.groupValues[2] }
                .maxByOrNull { it.length }
                .orEmpty()
            assertTrue(
                "$path: фраза на ${longest.length} знаків не влізе у два рядки підпису: «$longest»",
                longest.length <= 64,
            )
        }
    }

    @Test
    fun `without a key the action refuses out loud instead of queueing silently`() {
        val hub = read("app/src/main/java/local/phantom/companion/data/PeerLiveHub.kt")
        assertTrue(
            "сеанс без ключа заводиться й мовчки нікуди не їде",
            Regex("""if \(verdict == PeerLiveShare\.Reach\.NO_KEY\) return verdict""")
                .containsMatchIn(hub),
        )
        val strings = read("feature-stream/src/main/res/values-uk/strings.xml")
        val english = read("feature-stream/src/main/res/values/strings.xml")
        listOf(
            "peer_share_no_key", "peer_share_reach_nearby",
            "peer_share_reach_relay", "peer_share_reach_queued",
        ).forEach {
            assertTrue("немає українського рядка $it", strings.contains("\"$it\""))
            assertTrue("немає англійського рядка $it", english.contains("\"$it\""))
        }
    }

    // ── №4 ───────────────────────────────────────────────────────────────

    @Test
    fun `expiry reaches the journal, not only the screen`() {
        val hub = read("app/src/main/java/local/phantom/companion/data/PeerLiveHub.kt")
        assertTrue(
            "протухле гасне лише на склі — у журналі лишається рядок, з якого " +
                "чуже положення збирається назад",
            hub.contains("PeerLiveInbox.spent(") && hub.contains("forget("),
        )
        val mesh = read("app/src/main/java/local/phantom/companion/data/PeerMesh.kt")
        assertTrue("forgetLive не пише на диск", mesh.contains("fun forgetLive("))
    }

    // ── маршрут ──────────────────────────────────────────────────────────

    @Test
    fun `the route screen can hand the route to one chosen contact`() {
        val sheet = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapCommandSheet.kt",
        )
        assertTrue("з екрана маршруту немає дії «надіслати»", sheet.contains("peer_route_share_chip"))
        assertTrue("немає кому надсилати", sheet.contains("shareTargets"))
        val vm = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapViewModel.kt",
        )
        assertTrue("дія намальована, кадру не збирає", vm.contains("PeerLive.encodeRoute("))
        val host = read("app/src/main/java/local/phantom/companion/ui/home/MapTab.kt")
        assertTrue("кадр збирається й нікуди не їде", host.contains("sendPeerLetter ="))
    }

    @Test
    fun `the result of sending a route reaches the glass, not a dropped Boolean`() {
        val vm = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapViewModel.kt",
        )
        // Найтвердіша частина: результат НЕМА КУДИ впустити. Доки shareRoute
        // віддавав Boolean, обидва викликачі його впускали, і дотик виглядав
        // як зламаний застосунок.
        assertFalse(
            "shareRoute знову віддає значення, яке можна мовчки впустити",
            Regex("""fun shareRoute\([^)]*\):\s*Boolean""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(vm),
        )
        assertTrue("немає стану, який читає екран", vm.contains("val routeHandoff:"))
        val chrome = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapChrome.kt",
        )
        assertTrue(
            "екран не читає, чим скінчився дотик",
            chrome.contains("viewModel.routeHandoff.collectAsStateWithLifecycle()"),
        )
        val sheet = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapCommandSheet.kt",
        )
        assertTrue("смуга маршруту не малює відповіді", sheet.contains("shareHandoffLine("))
        val strings = read("feature-stream/src/main/res/values-uk/strings.xml")
        val english = read("feature-stream/src/main/res/values/strings.xml")
        listOf(
            "peer_route_no_plan", "peer_route_no_key",
            "peer_route_sent_mesh", "peer_route_sent_relay", "peer_route_sent_queued",
        ).forEach {
            assertTrue("немає українського рядка $it", strings.contains("\"$it\""))
            assertTrue("немає англійського рядка $it", english.contains("\"$it\""))
        }
    }

    /**
     * Дія жива, але переїхала — і сторож дивився на стару адресу.
     *
     * Вона висіла на картці людини (`ChamberDestinations.onTakeRoute`), і 06.09
     * пішла разом із проводкою рідного екрана розмови. Здатність НЕ загинула:
     * та сама бульбашка живе на аркуші мапи (`SheetHost`), а план будує
     * `MapViewModel.takePeerRoute`. Правило те саме, підмет інший — тому тест
     * перенацілено, а не знято: знятий сторож і є той спосіб, яким здатність
     * тихо гине наступного разу.
     */
    @Test
    fun `taking a route re-plans from my own position, never copies the line`() {
        val sheet = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/SheetHost.kt",
        )
        assertTrue("дія «їхати цим маршрутом» ні до чого не веде", sheet.contains("onTake = { route ->"))
        val takeBlock = sheet.substringAfter("onTake = { route ->").take(900)
        assertTrue(
            "план будується не від СВОГО місця",
            takeBlock.contains("viewModel.takePeerRoute("),
        )
        assertFalse("у план заїхала чужа ламана", takeBlock.contains("route.shape"))

        // Ключове: у планувальник ідуть чужі ТОЧКИ, а перша — своя власна.
        // Чужа ламана лишається малюнком.
        val vm = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapViewModel.kt",
        )
        val plan = vm.substringAfter("fun takePeerRoute(").take(600)
        assertTrue(
            "чужа геометрія поїхала в план як істина",
            plan.contains("route.waypoints.drop(1)") &&
                plan.contains("listOf(fromLat to fromLon) + via"),
        )
        assertFalse("у план заїхала чужа ламана", plan.contains("route.shape"))
    }

    /**
     * Три поверхні, на яких оператор бачить маршрут, і на жодній — дріт.
     *
     * Бульбашку малював рідний екран розмови; 06.09 він пішов, і його місце
     * зайняв веб. Правило те саме, підмет інший: те, що показує лист, мусить
     * РОЗІБРАТИ кадр, а не надрукувати `PH-RTE:1788…;337;258;FOOT;…`.
     */
    @Test
    fun `a route never shows the operator the raw wire`() {
        val kinds = read("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerKinds.kt")
        assertTrue("сирий PH-RTE у бульбашці веба", kinds.contains("PeerLive.decodeRoute("))
        val fmt = read(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/PeerChatFormat.kt",
        )
        assertTrue("сирий PH-RTE у рядку реєстру", fmt.contains("PeerLive.isRoute("))
        val container = read("app/src/main/java/local/phantom/companion/data/AppContainer.kt")
        assertTrue("сирий PH-RTE у шторці", container.contains("peer_route_notification"))
    }

    @Test
    fun `the receiving store cannot hold a second point for one person`() {
        val inbox = read(
            "core-net/src/main/java/local/phantom/companion/core/net/peer/PeerLiveInbox.kt",
        )
        // Історія неможлива за ТИПОМ, а не за домовленістю: якщо тут колись
        // зʼявиться список, слід чужого дня можна буде зібрати знову.
        assertTrue(
            "сховище приймача більше не «одна людина — одна точка»",
            inbox.contains("Map<String, Standing>"),
        )
        assertFalse(
            "у сховищі приймача зʼявився список положень — це маршрут чужого дня",
            Regex("""Map<String,\s*(List|MutableList|Set)<""").containsMatchIn(inbox),
        )
    }
}
