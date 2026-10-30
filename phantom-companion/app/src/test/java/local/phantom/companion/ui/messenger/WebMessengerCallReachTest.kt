package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Чи дзвінок справді ДОСЯЖНИЙ, а не просто написаний.
 *
 * Правильність кадру й того, що він перетинає дріт, доводиться в core-net
 * справжніми сокетами (`PeerCallReachTest`). Тут доводиться інше — і саме воно
 * було зламане: дорога від ручки мосту до другого апарата. Міст слав `POST` на
 * `http://<адреса>/api/v1/messenger/call/inbound`, а на тому кінці HTTP не
 * слухає ніхто, і жоден дзвінок не доходив узагалі.
 *
 * GATE LAW: читає файли як текст. Входи задачі оголошені.
 */
class WebMessengerCallReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        if (!f.isFile) fail("немає файла $rel")
        return f.readText()
    }

    /**
     * Джерело без рядків-коментарів.
     *
     * Сторож, який шукає мертву адресу підрядком, знаходив її у KDoc, що цю
     * саму адресу й хоронить, — і червонів на справному дереві. Викинуто лише
     * ЦІЛІ рядки коментаря: хвостовий `//` лишається, бо зайве збережене
     * червонить голосно, а зайве викинуте зеленить мовчки.
     */
    private fun codeOf(text: String): String = text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private val bridge by lazy {
        source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt")
    }
    private val container by lazy {
        source("app/src/main/java/local/phantom/companion/data/AppContainer.kt")
    }
    private val mesh by lazy {
        source("app/src/main/java/local/phantom/companion/data/PeerMesh.kt")
    }
    private val host by lazy {
        source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt")
    }
    // Хост може штовхати двері константою сусіднього класу
    // (`evaluateJavascript(WebMessengerReminders.CHANGED_JS, null)`): текст цієї константи — теж його виклик.
    private val hostCalls by lazy {
        val pushed = Regex("""evaluateJavascript\(\s*(\w+)\.(\w+)""").findAll(codeOf(host)).map { m ->
            val owner = runCatching { source("app/src/main/java/local/phantom/companion/ui/messenger/${m.groupValues[1]}.kt") }.getOrDefault("")
            Regex("""const val ${m.groupValues[2]}\s*=\s*"([^"]*)"""").find(owner)?.groupValues?.get(1).orEmpty()
        }
        val nav = source("app/src/main/java/local/phantom/companion/ui/messenger/WebNav.kt")
        (sequenceOf(host, codeOf(nav)) + pushed).joinToString("\n")
    }
    private val manifest by lazy { source("app/src/main/AndroidManifest.xml") }
    private val ice by lazy {
        source("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerIce.kt")
    }

    /**
     * Та сама вада, названа точно. Доки цей випадок зелений, у дереві немає
     * дороги, яку на тому кінці ніхто не слухає.
     */
    @Test
    fun `міст більше не бʼє HTTP-ом у сокет, який HTTP не читає`() {
        val code = codeOf(bridge)
        assertFalse(
            "сигнал дзвінка досі їде на /api/v1/messenger/call/inbound — " +
                "адресу, якої слухач телефона не розбирає",
            code.contains("/api/v1/messenger/call/inbound"),
        )
        assertFalse(
            "у мосту лишився власний відправник HTTP для дзвінків",
            code.contains("fun postHttpSignal("),
        )
        assertFalse(
            "сигнал дзвінка досі складає HttpURLConnection",
            code.contains("peer?.transport") && code.contains("HttpURLConnection"),
        )
    }

    @Test
    fun `сигнал дзвінка їде в сітку через живий вузол`() {
        for (needle in listOf(
            "container.callHub.post(",
            "peerId = peer.id,",
            "myNodeId = container.mesh.selfIdFlow.value",
        )) {
            assertTrue("у мосту немає «$needle»", bridge.contains(needle))
        }
    }

    /**
     * Рід сигналу — із ЗАКРИТОГО словника рушія.
     *
     * Слово, якого рушій не шле, мусить відмовитись, а не поїхати «чимось
     * схожим»: на тому кінці воно стало б не тим кадром, і дзвінок помер би
     * мовчки — після того, як людина вже побачила свій екран виклику.
     */
    @Test
    fun `невідомий рід сигналу не їде взагалі`() {
        assertTrue(
            "міст не звіряє рід зі словником рушія",
            bridge.contains("PeerCall.Signalling.ofWeb(kind) == null"),
        )
        val call = source(
            "core-net/src/main/java/local/phantom/companion/core/net/peer/PeerCall.kt",
        )
        for (kind in listOf("offer", "answer", "ice", "hangup", "radio")) {
            assertTrue(
                "рід «$kind» зник зі словника — рушій шле саме ці пʼять слів",
                call.contains("\"$kind\""),
            )
        }
    }

    /**
     * Рішення знято ЯВНО, не мовчки: власник 15.09 попросив групову
     * конференцію без стелі учасників, і 16.09 вона стала СІТКОЮ, а не
     * розсилкою: [PeerCallHub.invite] заводить у кімнаті ногу на КОЖНОГО
     * учасника гілки замість однієї ноги на `group.id`.
     *
     * Імʼя `postToGroup` тут навмисно заборонене: поки воно живе, у мосту
     * може стояти стара дорога, де другий учасник отримував «зайнято».
     */
    @Test
    fun `дзвінок у гілку заводить ногу кожному, а не розсилку`() {
        assertFalse(
            "стара відмова груповим дзвінкам лишилась у мосту",
            bridge.contains("group_calls_unsupported"),
        )
        assertFalse(
            "у мосту лишилась розсилка на одну ногу замість сітки",
            bridge.contains("postToGroup("),
        )
        for (needle in listOf(
            "handleGroupCallSignal(",
            "PeerGroup.fanOut(group, me)",
            "container.callHub.invite(",
        )) {
            assertTrue("у мосту немає «$needle»", bridge.contains(needle))
        }
    }

    /**
     * Стеля кімнати мусить бути ВИДИМОЮ людині, а не тихо різати список.
     *
     * Власник просив «безмежно». Безмежно апарат не витримає, і чесна відповідь
     * — конкретне число плюс те, що людина його ПРОЧИТАЄ: `too_many` каже,
     * скільком не вистачило місця, `max_legs` — скільки їх узагалі буває.
     */
    @Test
    fun `кімната каже, кого не вмістила, а не мовчить`() {
        for (needle in listOf(
            """put("too_many", fan.tooMany)""",
            """put("max_legs", PeerCallLine.MAX_LEGS)""",
            "msgr_bridge_call_room_full",
        )) {
            assertTrue("міст мовчить про стелю кімнати: немає «$needle»", bridge.contains(needle))
        }
    }

    /**
     * Ноги, заведені поки WebView не було на екрані, існують ЛИШЕ в телефоні.
     * Без ручки, яка їх віддає, рушій домальовував би кімнату з власної
     * памʼяті — тобто вигадував би її.
     */
    @Test
    fun `веб має де спитати склад кімнати і як із неї вийти`() {
        for (needle in listOf(
            """seg.getOrNull(2) == "room" && method == "GET" -> callRoom()""",
            "container.callHub.leaveNow()",
            "container.callHub.dropLeg(who)",
        )) {
            assertTrue("у мосту немає «$needle»", bridge.contains(needle))
        }
        assertTrue(
            "дії над кімнатою поїдуть у розбирач родів як невідомий рід",
            bridge.contains("CALL_ROOM_VERBS"),
        )
    }

    /**
     * Контракт кімнати: міст віддає рівно ті поля, які читає веб.
     *
     * Веб малює склад кімнати й попередження про стелю зі СВОЇХ полів, і
     * добути їх більше нізвідки не може: `PhantomBridge` — це весь його
     * доступ до апарата. Поле, яке міст перестане слати, не впаде помилкою —
     * воно стане `undefined`, і картка тихо перестане показувати те, заради
     * чого її й писали.
     *
     * Другу половину дзеркала тримає `callRoom.test.ts` у веб-дереві. Читати
     * звідси чуже дерево було б гірше за розділене дзеркало: на воротах того
     * дерева немає, і звірка не почервоніла б, а ПРОПУСТИЛАСЬ — тобто мовчки
     * перестала б бути звіркою.
     */
    @Test
    fun `кімната віддає вебу рівно ті поля, які він читає`() {
        val room = bridge.substringAfter("private fun callRoom()").substringBefore("\n    }")
        for (field in listOf(
            "call_id", "group_id", "legs", "max_legs", "crowded_at",
            "peer_id", "name", "foot", "since_ms", "why", "reachable",
        )) {
            assertTrue(
                "міст не віддає полю кімнати «$field» — у вебі воно стане undefined",
                room.contains("put(\"" + field + "\""),
            )
        }

        // Стеля й поріг тісноти їдуть ІЗ КОДУ, а не числами в JSON: друга
        // копія числа розійшлася б із першою на першій же правці.
        assertTrue(
            "стеля кімнати перетворилась на літерал у мосту",
            room.contains("PeerCallLine.MAX_LEGS") && room.contains("PeerCallLine.CROWDED_LEGS"),
        )
    }

    /**
     * `inbound` був глухим `false`, і це було ПРАВДОЮ: приймати не було чим.
     * Тепер це має бути вимір, а не нова константа в інший бік.
     *
     * 09.10 вимір переїхав із мосту в [WebMessengerIce] (`container.webMessengerIce`,
     * ICE-N) — цей тест стереже те саме, але за новою адресою, інакше він
     * червонів би на справному дереві, шукаючи рядок, якого тут більше нема.
     */
    @Test
    fun `оголошення про вхідні дзвінки стало виміром`() {
        assertFalse(
            "inbound знову оголошується наперед",
            bridge.contains("""put("inbound", false)""") ||
                bridge.contains("""put("inbound", true)"""),
        )
        assertTrue(
            "маршрут ICE не веде до WebMessengerIce",
            bridge.contains("container.webMessengerIce.answer()"),
        )
        assertTrue(
            "inbound не питає слухача",
            ice.contains("""put("inbound", inbound())""") &&
                ice.contains("inbound = { container.mesh.isListening }"),
        )
        assertTrue("сітка не оголошує, чи слухач піднятий", mesh.contains("val isListening: Boolean"))
    }

    /**
     * Досяжність ICE по ланках — від маршруту веба до сокета сітки. Бридж у
     * `:app` не збирається в юніт-тесті (контейнеру потрібен Android), тож
     * справжній `GET /messenger/ice` через `dispatch` тут не пройти; зате
     * кожну ланку ланцюга видно окремо, і розрив у будь-якій червонить сам.
     * Конверт відповіді `WebMessengerIce.answer()` звіряє `WebMessengerIceTest`
     * (ok/status/data) — той самий, що збирає `ok()` у мосту.
     */
    @Test
    fun `дорога ICE цілісна від маршруту веба до слухача сітки`() {
        val route = codeOf(bridge)
        assertTrue(
            "GET /messenger/ice у мосту не веде до WebMessengerIce",
            Regex("""seg\[1\]\s*==\s*"ice"\s*&&\s*method\s*==\s*"GET"\s*->\s*container\.webMessengerIce\.answer\(\)""")
                .containsMatchIn(route),
        )
        assertTrue(
            "AppContainer не будує webMessengerIce через WebMessengerIce.of(this)",
            Regex("""val webMessengerIce\b[^\n]*by lazy\s*\{\s*[^}]*WebMessengerIce\.of\(this\)""")
                .containsMatchIn(codeOf(container)),
        )
        val of = codeOf(ice).substringAfter("fun of(container: AppContainer)")
        assertTrue(
            "WebMessengerIce.of не бере адресу вузла зі збірки",
            of.contains("baseUrl = BuildConfig.LICENSE_BASE_URL"),
        )
        assertTrue(
            "WebMessengerIce.of не питає слухача сітки",
            of.contains("inbound = { container.mesh.isListening }"),
        )
        assertTrue(
            "WebMessengerIce.of не читає turn.json із даних застосунку",
            of.contains("filesDir = ctx.filesDir"),
        )
        assertFalse(
            "міст знову має власну копію iceConfig — дві дороги ICE розійдуться",
            Regex("""fun iceConfig\(""").containsMatchIn(route),
        )
    }

    @Test
    fun `кадр дзвінка має куди приїхати і чим поїхати`() {
        assertTrue(
            "сітка не кличе нікого на вхідний кадр дзвінка",
            container.contains("onCall = { callHub.absorb(it) }"),
        )
        assertTrue(
            "вузол дзвінка не має дороги назад",
            container.contains("mesh.sendCallFrame(peerId, frame)"),
        )
        assertTrue(
            "сітка не вміє слати кадр дзвінка прямо",
            mesh.contains("suspend fun sendCallFrame("),
        )
        // Кадр дзвінка не лягає в журнал: там жив би SDP, а журнал шифрується
        // й переписується ЦІЛКОМ на кожен запис.
        assertTrue(
            "вхідний кадр дзвінка лягає в журнал розмов",
            mesh.contains("if (PeerCall.isCall(message.body)) {"),
        )
    }

    /** Плашка мусить ПІДІЙМАТИ апарат, інакше дзвінок пропущено уві сні. */
    @Test
    fun `дзвінок підіймає апарат, а не лягає рядком у шторку`() {
        val notif = source(
            "app/src/main/java/local/phantom/companion/service/PeerCallNotifications.kt",
        )
        assertTrue("немає повноекранного наміру", notif.contains("setFullScreenIntent("))
        assertTrue("плашка не оголошена дзвінком", notif.contains("CATEGORY_CALL"))
        assertTrue("немає двох кнопок дзвінка", notif.contains("CallStyle.forIncomingCall("))
        assertTrue(
            "приймач кнопок не оголошено в маніфесті — «взяти» не спрацює",
            manifest.contains(""".service.PeerCallActionReceiver""""),
        )
        assertTrue(
            "адреси дзвінка немає в маніфесті — тап по плашці нікуди не веде",
            manifest.contains("""android:host="call""""),
        )
    }

    /**
     * `phantom://call` мусить розібратись ПЕРШИМ із адресних розбирачів.
     *
     * Порядок тут — не смак: розбирачі йдуть ланцюжком і кожен бере адресу собі,
     * щойно впізнав схему. Якби гілка дзвінка стояла після `phantom://peer`,
     * тап по плашці відчиняв би розмову з людиною на імʼя «call».
     */
    @Test
    fun `тап по плашці дзвінка не дістається чужому розбирачеві`() {
        val activity = codeOf(source("app/src/main/java/local/phantom/companion/MainActivity.kt"))
        val call = activity.indexOf("openCallIntent(intent)")
        assertTrue("phantom://call ніхто не розбирає", call >= 0)
        listOf("acceptInviteIntent(intent)", "openPeerGroupIntent(intent)").forEach { later ->
            val at = activity.indexOf(later)
            if (at >= 0) {
                assertTrue("«$later» перехоплює phantom://call раніше за дзвінок", call < at)
            }
        }
        assertTrue(
            "гілка дзвінка не питає host — адреса дісталась би наступному розбирачеві",
            activity.contains("PeerCallNotifications.HOST_CALL"),
        )
    }

    /**
     * ДЗЕРКАЛО: читач у бандлі має писаря в хості.
     *
     * Рівно та вада, яку власник бачив як «дзвінки 0»: зібраний веб оголошував
     * `window.__phantomCallSignal`, а котлін не кликав його ЖОДНОГО разу, тож
     * сигнал, який доїхав, не мав кому лягти.
     *
     * Розрізняє два роди імен, і різниця видима в самому написанні:
     * `__phantomМалаВелика` — двері, які веб ВІДЧИНЯЄ хостові; `__phantom_з_
     * підкресленнями` — власні глобалі веба (сховок, меш), до яких хост не
     * ходить і ходити не має.
     */
    @Test
    fun `кожні двері бандла мають викликача в хості`() {
        val js = bundleJs()
        // Перший знак після `__phantom` і є розрізненням: літера — двері хоста,
        // підкреслення — власний глобаль веба.
        val sinks = Regex("""window\.(__phantom[A-Za-z0-9][A-Za-z0-9]*)\s*=""")
            .findAll(js)
            .map { it.groupValues[1] }
            .toSortedSet()
        assertTrue("у бандлі не знайдено жодних дверей — перевір, чи тест ще про те саме", sinks.isNotEmpty())

        // Оголошений виняток, а не мовчазний пропуск: `__phantomCallAudio` —
        // це `()=>Vx.debug()`, ручка ДО СЕБЕ для людини з консоллю (стан
        // синтезатора дзвінка: ctx, master, voices, gains). Писаря вона не
        // потребує й не дістане: котлін, який почав би її кликати, смикав би
        // чужий зумер повз рушій. Імʼя порушує домовленість про написання —
        // виняток тут саме через це, а не через відкладену роботу.
        val ownDebug = setOf("__phantomCallAudio")
        val orphans = sinks.filterNot { it in ownDebug }.filterNot { hostCalls.contains(it) }
        assertEquals(
            "бандл відчиняє двері, у які котлін не стукає — те, що доїхало, " +
                "не має кому лягти",
            emptyList<String>(),
            orphans,
        )
        assertTrue(
            "двері дзвінка зникли з бандла — хост кличе те, чого немає",
            sinks.contains("__phantomCallSignal"),
        )
    }

    /**
     * ВИМІР про зібраний веб: рушій дзвінка там СПРАВЖНІЙ.
     *
     * Задачу ставили з припущення, що бандл лише вдає дзвінок. Це не так, і
     * перевірено тут: він бере мікрофон, кладе доріжки в зʼєднання й складає
     * пропозицію. Тому поверхня дзвінка — веб, а котлін везе кадр і підіймає
     * апарат. Цей випадок почервоніє того дня, коли рушій із бандла зникне, —
     * і тоді хтось має вирішити, хто дзвонить замість нього, а не виявити
     * мовчазну тишу на склі.
     */
    @Test
    fun `рушій дзвінка в бандлі справжній, а не фасад`() {
        val js = bundleJs()
        for (needle in listOf(
            "messenger/call",
            "__phantomCallSignal",
            "getUserMedia",
            "createOffer",
            "addTrack",
        )) {
            assertTrue("у бандлі немає «$needle» — рушій дзвінка зник", js.contains(needle))
        }
        assertTrue(
            "бандл перестав питати перелік ICE — перевір, чи тест ще про те саме",
            js.contains("messenger/ice"),
        )
        assertTrue(
            "слухавка більше не питає, чи телефон приймає вхідні — " +
                "вона знову дзвонитиме в порожнечу",
            js.contains("inbound"),
        )
    }

    private fun bundleJs(): String {
        val assets = File(repoRoot(), "app/src/main/assets/webmsgr/assets")
        assertTrue("бандла немає — перевір шлях", assets.isDirectory)
        return assets.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".js") }
            ?.joinToString("\n") { it.readText() }
            ?: fail("у бандлі немає жодного .js") as Nothing
    }
}
