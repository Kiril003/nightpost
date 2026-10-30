package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ФАЄРВОЛ БАНДЛА ТЕЛЕФОНА.
 *
 * 03.09 вимір APK 55d946b4: у `assets/webmsgr` лежали 57 показових модалок
 * (`*Modal-*.js`, 620 КБ — студенти «Кирило»×25, autograderScore 95/100, курси
 * й дедлайни, яких не існує) з дверима у шапці, і 75 файлів `.js.map` (9 з 19 МБ),
 * хоча «.js.map геть» ухвалено 29.08 і зняте тоді ж. Це повернулось, бо бандл
 * копіюється в APK руками з `dist/`, а нічого не дивилось, що саме приїхало.
 *
 * Правило: у бандлі телефона нема ані карт джерел, ані чанків показових модалок
 * поза білим переліком (кожен елемент переліку — з причиною). Червоніє на самому
 * артефакті в дереві, а не на джерелі веба — бо саме артефакт їде до людини.
 */
class WebBundleFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val MIN_FILES = 20

    private val bundle get() = File(repoRoot(), "app/src/main/assets/webmsgr")

    /** Чанки-модалки, яким дозволено бути в бандлі телефона, — і чому. */
    private val allowedModalChunks: Map<String, String> = mapOf(
        // (порожньо: усе, що обслуговує міст, живе не в *Modal-* чанках)
    )

    private fun files(): List<File> =
        bundle.walkTopDown().filter { it.isFile }.toList().also {
            // Нуль файлів означав би, що бандла нема — тоді й зелене нічого не варте.
            // Підлога — не «старий бандл»: до редизайну їх було 265, після — 33
            // (index.html, BUNDLE_SOURCE, шрифти, VAD і чанки). 20 ловить і
            // порожній, і обрізаний бандл, але не червоніє від злиття чанків.
            check(it.size >= MIN_FILES) { "бандл у ${bundle.path} порожній або відсутній: ${it.size} файлів" }
        }

    @Test
    fun `вихідний код веба не їде в телефон — жодного sourcesContent`() {
        // Головна умова: карту можна лишити без вмісту джерел, і тоді назва «.js.map»
        // пройде повз попередній тест, а розкриття лишиться. Ловимо суть, не назву:
        // у 55d946b4 усі 75 карт несли sourcesContent — 99 файлів .tsx читабельним текстом.
        val leaking = files()
            .filter { it.length() < 20L * 1024 * 1024 }
            .filter { f -> f.bufferedReader().use { r -> r.lineSequence().any { it.contains("\"sourcesContent\"") } } }
            .map { it.name }.sorted()
        assertEquals(
            "у бандлі телефона є файли з вихідним кодом веба (\"sourcesContent\", ${leaking.size}) — " +
                "це розкриття джерел людям, яким продають безпеку; phone-режим має sourcemap=false:\n    " +
                leaking.joinToString("\n    "),
            emptyList<String>(),
            leaking,
        )
    }

    @Test
    fun `бандл підключається з-під точки монтування webmsgr — інакше WebView дістає text-plain і порожню сторінку`() {
        // 04.09 01:30, перший кадр на склі: index.html посилався на /assets/index-*.js від
        // кореня, перехоплювач пускає лише https://phantom.local/webmsgr/** — три модулі
        // «MIME text/plain», рендерер упав, месенджер порожній у КОЖНІЙ збірці дня.
        // Уночі бандл будувався з `--base /webmsgr/`, денні перезбірки — без нього.
        val index = File(bundle, "index.html").readText()
        // Без винятків, і іконка вкладки теж: виняток «це не важливо для WebView» —
        // це рівно те місце, де наступне посилання від кореня пройде непоміченим.
        val refs = Regex("""(?:src|href)="([^"]+)"""").findAll(index).map { it.groupValues[1] }
            .filter { it.startsWith("/") || it.startsWith("http") }
            .toList()
        val outside = refs.filterNot { it.startsWith("/webmsgr/") }
        assertEquals(
            "index.html підключає ресурси поза /webmsgr/ (${outside.size}) — WebView їх не отримає; " +
                "збирати з base=/webmsgr/:\n    " + outside.joinToString("\n    "),
            emptyList<String>(),
            outside,
        )
        check(refs.isNotEmpty()) { "index.html без жодного src/href — сторож нічого не перевірив" }
    }

    @Test
    fun `карти джерел не їдуть у телефон`() {
        val maps = files().filter { it.name.endsWith(".js.map") }.map { it.name }.sorted()
        assertEquals(
            "у бандлі телефона знайдено карти джерел (${maps.size}); збирати веб для APK лише " +
                "phone-режимом (sourcemap=false):\n    " + maps.joinToString("\n    "),
            emptyList<String>(),
            maps,
        )
    }

    /**
     * ЗАМОК ДРУГОГО ТРАНСПОРТУ.
     *
     * 04.09 суперечка зламала твердження «перехоплювач не пускає нікуди»:
     * воно справджується лише для запитів, що ПРОХОДЯТЬ через
     * `shouldInterceptRequest`, а рукостискання WebSocket повз нього не йде
     * взагалі. У бандлі, який поїхав у `app-field-arm64-v8a-release`
     * (index-B0rkLpLo.js, sha256 27e86b47…), виміряно 4 виклики
     * `new WebSocket(` і читання `VITE_MESH_BROKER_URL`.
     *
     * Тому огорожа стоїть у двох місцях, і обидва — тут:
     *   CSP на самому документі — жоден ЧУЖИЙ хост (публічний MQTT-брокер);
     *   заглушка перед скриптами сторінки — свій `wss://phantom.local/ws`,
     *   якого `connect-src 'self'` за специфікацією НЕ спиняє.
     */
    @Test
    fun `сторінка їде з політикою, і та політика не пускає до чужого хоста`() {
        val csp = WebMessengerAssets.CSP

        // Обидва шляхи HTML: сама сторінка і запасний для маршрутів усередині
        // веба. Без другого глибоке посилання дістало б сторінку без політики.
        val bytes = { _: String -> "<!doctype html>".byteInputStream() }
        val page = WebMessengerAssets.resolve(WebMessengerAssets.ENTRY, bytes)
        val deep = WebMessengerAssets.resolve("${WebMessengerAssets.ORIGIN}/webmsgr/chat/123") { name ->
            if (name == WebMessengerAssets.INDEX) "<!doctype html>".byteInputStream() else null
        }
        val mimes = listOf(
            "сторінка" to (page as? Served.Asset)?.mime,
            "маршрут усередині веба" to (deep as? Served.Fallback)?.mime,
        )
        for ((what, mime) in mimes) {
            check(mime == "text/html") { "$what прийшла не як text/html ($mime) — політика не поїде" }
            assertEquals(
                "$what їде без Content-Security-Policy",
                csp,
                WebMessengerAssets.headersFor(mime)["Content-Security-Policy"],
            )
        }

        val connect = Regex("""connect-src([^;]*)""").find(csp)?.groupValues?.get(1)?.trim()
            ?: error("у політиці нема connect-src — саме він і стереже сокети")
        // Джерела, що відчиняють чужий хост. `'self'` і схеми blob:/data: —
        // свої; будь-що інше (зірка, гола схема http:/https:/ws:/wss:, явний
        // хост) означає дорогу назовні.
        val foreign = connect.split(" ").filter { it.isNotBlank() }
            .filterNot { it in setOf("'self'", "blob:", "data:") }
        assertEquals(
            "connect-src пускає не лише до себе ($foreign) — саме так публічний брокер і повернеться",
            emptyList<String>(),
            foreign,
        )
    }

    @Test
    fun `заглушка сокета не кидає і не лишає переспроб`() {
        val stub = WebMessengerAssets.NO_WEBSOCKET
        // Кидок тут був би гіршим за мовчання: веб ловить конструктор у `try`
        // і на кидок САМ призначає переспробу — вічний цикл 1с→30с лишився б.
        check(!stub.contains("throw")) { "заглушка кидає — веб піде в нескінченні переспроби" }
        // Навіки CONNECTING: подій не буде, отже не буде ані onclose, ані
        // переспроби, а перевірка «вже підʼєднуюсь» відсіче повторні виклики.
        check(stub.contains("this.readyState=0")) { "заглушка не лишається в CONNECTING" }
        check(stub.contains("S.OPEN=S.prototype.OPEN=1")) { "заглушці бракує сталих WebSocket.*" }
        check(stub.contains("writable:false")) { "заглушку можна перезаписати з самої сторінки" }
    }

    /**
     * 07.09 адреси STUN у бандлі БУЛИ — три сервери Google за умовою
     * `window.PhantomBridge ? [] : [...]`, і сторож стеріг саме умову: приберуть
     * її — телефон піде на чужий сервер сам. 12.09 (веб `ab360f71`) літералів
     * не стало: обидва рушії беруть список лише з відповіді вузла
     * `GET /messenger/ice` (`services/iceRoads.ts`). Мета сторожа — витік, а не
     * наявність адреси, тож нуль адрес зелений; умова моста далі перевіряється
     * для кожної адреси, яка колись зʼявиться; а те, що сторож справді читав
     * бандл, доводить наявність самого запиту до вузла.
     */
    @Test
    fun `кожна адреса STUN-TURN у бандлі лишається за умовою моста`() {
        val ice = Regex("""["'](?:stuns?|turns?):[A-Za-z0-9.-]+\.[A-Za-z0-9.-]+[^"']*["']""")
        val ungated = mutableListOf<String>()
        var asksNode = 0
        val scripts = files().filter { it.name.endsWith(".js") || it.name.endsWith(".html") }
        for (file in scripts) {
            val text = file.readText()
            for (m in ice.findAll(text)) {
                // Мініфікатор тримає умову й перелік в одному виразі; імʼя
                // `PhantomBridge` глобальне, тож перейменування його не чіпає.
                val from = (m.range.first - 400).coerceAtLeast(0)
                if (!text.substring(from, m.range.first).contains("PhantomBridge")) {
                    ungated += "${file.name}: ${m.value}"
                }
            }
            if (text.contains("/messenger/ice")) asksNode++
        }
        assertEquals(
            "у бандлі телефона є адреса STUN/TURN без умови `window.PhantomBridge` (${ungated.size}) — " +
                "телефон піде на чужий сервер сам:\n    " + ungated.joinToString("\n    "),
            emptyList<String>(),
            ungated,
        )
        check(scripts.size > 1) { "у бандлі нема скриптів — сторож нічого не прочитав" }
        check(asksNode > 0) { "у бандлі нема запиту /messenger/ice — дороги дзвінка не питають вузол, сторож не спрацював" }
    }

    /**
     * 12.09: у WebView сокета немає (заглушка NO_WEBSOCKET), а міст не приносить
     * у сторінку ні відповіді на дзвінок, ні реакцій, ні сигналів DataChannel.
     * Контроли, чий транспорт лише сокет, — слухавка й камера, реакції, ручний
     * SDP-офер, вибір транспорту — у phone-збірці вирізано (веб wave/web-12sep);
     * слухавка лишається і малюється лише при inbound:true з GET /messenger/ice,
     * щоб бульбашка не показувала стан, якого ніхто не підтвердив. Тут це
     * доводиться на самому артефакті.
     */
    @Test
    fun `у бандлі телефона нема контролів, чий транспорт — сокет`() {
        // 18.09, з дозволу власника: реакції пішли з цього списку не з
        // поблажливості, а тому що передумова списку перестала бути правдою.
        // Ручку мосту (`…/messages/{id}/reactions`, POST) написали 13.09 і не
        // кликали з веба жодного разу — саме тому вони тут і стояли. Тепер
        // виклик у бандлі є, і доведено це на АРТЕФАКТІ, не комітом:
        //
        //   react:(e,t,n)=>k("POST",`…/messages/${t}/reactions`,{emoji:n})
        //   function k(…){const s=globalThis.PhantomBridge; s.call(…)}
        //
        // тобто транспорт — рідний міст, не сокет. На тому кінці `react()`
        // звіряє належність листа розмові, вимагає ключ співрозмовника
        // (без ключа — чесні 409), і віддає кадр у меш, а при невдачі каже
        // 503, а не «надіслано».
        //
        // Заборона ІМЕНІ тут була слабшим сторожем, ніж доказ ДОРОГИ: вона
        // однаково мовчала б, якби контрол просто перейменували. Нижче —
        // «як контрол є, то й дорога є».
        val markers = listOf("Термінал P2P", "Протокол за замовчуванням")
        val scripts = files().filter { it.name.endsWith(".js") }
        val found = scripts.flatMap { f ->
            val text = f.readText()
            markers.filter { text.contains(it) }.map { "${f.name}: $it" }
        }
        assertEquals(
            "у бандлі телефона повернулись контроли без транспорту (${found.size}):\n    " +
                found.joinToString("\n    "),
            emptyList<String>(),
            found,
        )
        // Контрол реакції без дороги — та сама вада, лише під іншим іменем.
        val reactionControl = scripts.any { f ->
            val t = f.readText()
            t.contains("Додати реакцію") || t.contains("Більше емодзі") || t.contains("Інші реакції")
        }
        if (reactionControl) {
            assertTrue(
                "у бандлі є контрол реакції, але жоден скрипт не звертається до …/reactions — " +
                    "смайл нікуди не поїде (13.09–18.09 саме так і було)",
                scripts.any { it.readText().contains("/reactions") },
            )
            assertTrue(
                "дорога реакції в бандлі є, але в тому ж скрипті немає PhantomBridge — " +
                    "у WebView сокета немає (заглушка NO_WEBSOCKET), тож це знову дорога в нікуди",
                scripts.any { f ->
                    val t = f.readText()
                    t.contains("/reactions") && t.contains("PhantomBridge")
                },
            )
        }
        check(scripts.any { it.readText().contains("/messenger/conversations") }) {
            "у бандлі нема запитів /messenger/conversations — сторож нічого не прочитав"
        }
    }

    /**
     * ДОСЯЖНІСТЬ ПУБЛІКАЦІЇ СИГНАЛІВ, а не наявність рядка.
     *
     * До 14.09 у бандлі лежали живі виклики `new WebSocket(`; адресу їм давав
     * `window.location`, тож чужого хоста в них не було — але свій
     * `wss://phantom.local/ws` `connect-src 'self'` за специфікацією НЕ спиняє.
     * Єдине, що їх глушило, — [WebMessengerAssets.NO_WEBSOCKET], і ставиться
     * він лише там, де WebView підтримує `DOCUMENT_START_SCRIPT`.
     *
     * 14.09 (зведення messengerStore, wave/webfirewall-04sep): конструктор
     * прибрано з phone-збірки ще на рівні джерела — `messengerNetworkEngine.
     * connectWebSocket()` порожній, `globalP2PMesh.connect()` виходить одразу
     * за компіляційним прапорцем `__PHONE_BUILD__` (Vite вкарбовує його як
     * `true`, ідентифікатор у бандлі не лишається — звіряти нема з чим
     * текстовим пошуком). Це СИЛЬНІША гарантія, ніж рантайм-заглушка: сокет
     * тепер не існує на рівні джерела, а не лише глушиться в WebView.
     *
     * Перевірка нижче — з тих часів, коли живий сокет ще існував, і рахунок
     * `sockets > 0` тоді ловив саме той факт. Тепер він ловив би ВЛАСНЕ
     * покращення як «зламане» — знято, а не заховано мовчки (див.
     * GuardRemovalFirewallTest: контракт змінено свідомо, сторожа
     * переписують). Ланцюг заглушка→міст→сторінка→відмова в
     * WebMessengerHost.kt нижче лишається чинним сам по собі — це захист
     * про запас на випадок, якщо `__PHONE_BUILD__` колись відкотиться або
     * новий вендор-чанк принесе власний сокет.
     *
     * Замок і міст мусять стояти в ОДНІЙ гілці зі сторінкою, а інакше —
     * [WebMessengerAssets.UNGUARDED_PAGE] замість месенджера.
     */
    @Test
    fun `без заглушки сторінка не відкривається — публікація сигналів недосяжна`() {
        val page = WebMessengerAssets.UNGUARDED_PAGE
        check(!page.contains("<script")) { "сторінка відмови несе скрипт — це остання сторінка, якій вірять" }
        check(page.contains("Месенджер не відкрито")) { "сторінка відмови не називає, що сталось" }

        val host = File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt")
            .readText()
        val stub = host.indexOf("addDocumentStartJavaScript")
        val bridge = host.indexOf("addJavascriptInterface")
        val entry = host.indexOf("loadUrl(WebMessengerAssets.ENTRY)")
        val refusal = host.indexOf("WebMessengerAssets.UNGUARDED_PAGE")
        check(stub > 0 && bridge > 0 && entry > 0 && refusal > 0) {
            "у хості бракує однієї з чотирьох ланок: заглушка=$stub міст=$bridge сторінка=$entry відмова=$refusal"
        }
        // Порядок у тексті — це і є гілка: підняти міст або сторінку вище
        // заглушки означає вантажити їх і там, де замка нема.
        check(bridge > stub && entry > stub) {
            "міст або сторінка виїхали з-під перевірки заглушки — на WebView без " +
                "DOCUMENT_START_SCRIPT сокети й STUN знову живі"
        }
        check(refusal > entry) { "гілка відмови стоїть не після завантаження сторінки — це вже не `else`" }
    }

    @Test
    fun `у бандлі нема сокета на чужий хост — інакше CSP не встиг би`() {
        // Адреси сокетів у бандлі рахуються з `window.location`, тож чужого
        // хоста в них нема за побудовою. Літерал зʼявиться рівно тоді, коли
        // бандл зберуть із заданим VITE_MESH_BROKER_URL — Vite вкарбує його
        // рядком. Виміряно на бандлі, що поїхав: 0.
        //
        // ЦЕЙ сторож стереже лише прапорець збірки і сьогодні зелений за
        // побудовою — сам по собі він нічого не доводить. Досяжність публікації
        // тримає сторож вище («без заглушки сторінка не відкривається»).
        val literals = files()
            .filter { it.name.endsWith(".js") || it.name.endsWith(".html") }
            .flatMap { f -> Regex("""wss?://[A-Za-z0-9._%-]+""").findAll(f.readText()).map { it.value } }
            .distinct().sorted()
        assertEquals(
            "у бандлі телефона є адреса сокета на чужий хост (${literals.size}) — публічний брокер, " +
                "який цей проєкт уже одного разу прибрав; збирати без VITE_MESH_BROKER_URL:\n    " +
                literals.joinToString("\n    "),
            emptyList<String>(),
            literals,
        )
    }

    /**
     * Замок стоїть у `WebMessengerHost`, і перевірити його виконанням тут
     * нічим: це `WebView` і `WebViewClient`. Тож дивимось на текст — грубо,
     * але саме воно червоніє, коли рядок приберуть. Живий доказ — на склі.
     */
    @Test
    fun `перехоплювач лишається зшитим із заглушкою, політикою і живим посиланням`() {
        val host = File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt")
            .readText()
        check(host.contains("addDocumentStartJavaScript")) {
            "заглушку сокета більше не ставлять перед скриптами сторінки"
        }
        check(host.contains("NO_WEBSOCKET")) { "заглушку сокета відʼєднали від хоста" }
        check(host.contains("headersFor")) { "документ більше не отримує заголовка політики" }
        // 04.09: тут стояло саме лише `true` — «не вантаж», без браузера й без
        // відмови. Палець по посиланню не робив рівно нічого.
        check(host.contains("openOutside(context, url)")) {
            "зовнішнє посилання знову нікуди не веде: палець натискає, нічого не стається"
        }
    }

    @Test
    fun `назовні йдуть лише зрозумілі схеми`() {
        for (ok in listOf("https://example.org/a", "http://example.org")) {
            check(WebMessengerAssets.opensOutside(ok)) { "$ok мав відкритись, а не мовчати" }
        }
        // Текст листа пише інша людина: `intent://…#Intent;…end` — це вибір
        // компонента чужими руками, а не адреса. `phantom://` теж не пускаємо:
        // мертвий дотик лагодимо, нових дверей у власний обробник не даємо.
        for (bad in listOf(
            "phantom://invite/AAAA",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "file:///data/data/local.phantom.companion/databases/peers.db",
            "data:text/html,<script>fetch('//x')</script>",
            "content://media/external/images/1",
        )) {
            check(!WebMessengerAssets.opensOutside(bad)) { "$bad пішов у систему — так не можна" }
        }
    }

    @Test
    fun `показових модалок у бандлі телефона нема поза білим переліком`() {
        val modals = files()
            .filter { it.name.endsWith(".js") && Regex("""Modal-[A-Za-z0-9_-]+\.js$""").containsMatchIn(it.name) }
            .map { it.name.substringBefore("-") }
            .filterNot { it in allowedModalChunks }
            .sorted()
        assertEquals(
            "у бандлі телефона є чанки модалок поза білим переліком (${modals.size}) — це показовий " +
                "вміст, якого міст не везе; збирати phone-режимом або вписати в перелік З ПРИЧИНОЮ:\n    " +
                modals.joinToString("\n    "),
            emptyList<String>(),
            modals,
        )
    }
}
