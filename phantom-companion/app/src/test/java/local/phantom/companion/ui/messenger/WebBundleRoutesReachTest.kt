package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * ДЗЕРКАЛО КОНТРАКТУ: кожен виклик мосту з бандла має маршрут.
 *
 * 12.09 вимір: веб із 09.09 кличе `PATCH /messenger/conversations/{id}/messages/{id}`,
 * а в [WebMessengerBridge] такого маршруту не було — 404. Контрол «Редагувати»
 * при цьому лежав у бандлі телефона живий, тож людина бачила, як її правка
 * блимнула й відкотилась: веб оптимістично ставить `isEdited`, а на відмову
 * вертає старий текст.
 *
 * Жоден зі сторожів цього не бачив, і не міг: сторож бандла звіряє УКРАЇНСЬКІ
 * ПІДПИСИ вирізаних контролів («Додати реакцію», «Більше емодзі»), а «Редагувати»
 * в його переліку немає — контрол лишався, бо його й не збирались прибирати.
 * Відсутній маршрут не має підпису взагалі: його видно лише там, де зустрілись
 * артефакт і джерело.
 *
 * Тому перевірка тут не на назву, а на ДОСЯЖНІСТЬ: усі виклики витягуються з
 * того самого `assets/webmsgr`, що їде в APK, усі гілки — з джерела мосту, і
 * різниця має бути порожня, крім названих винятків.
 */
class WebBundleRoutesReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val bundle get() = File(repoRoot(), "app/src/main/assets/webmsgr")

    private val sources get() =
        File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger")

    private data class Call(val method: String, val path: String) {
        override fun toString(): String = "$method $path"
    }

    /**
     * Виклики, на які маршруту немає СВІДОМО, — і чим це чесно.
     *
     * Порожній рядок причини тут не приймається: виняток без причини наступний
     * читач прийме за недогляд і «полагодить» маршрутом, якого не має бути.
     */
    private val knownUnserved: Map<String, String> = mapOf(
        // 20.09: перелік спорожнів, і це не послаблення — навпаки.
        //
        // Тут стояв `POST /messenger/exit` з причиною «веб ловить цю відмову
        // сам і каже людині словом». На апараті це виміряли: три дотики по
        // стрілці «Вийти з месенджера» [0,88][126,214] — екран не змінився
        // (t2-38 проти t2-39). А найчеснішим наслідком була б фраза «вийти
        // можна системною кнопкою «назад»», тобто застосунок просить людину
        // зробити його роботу.
        //
        // Кнопку знято, а з нею й `messengerApi.leave()`: виклик зник із
        // бандла, і винятку більше нема чого прикривати. Виходом і далі
        // володіє андроїд — апаратний BACK виводить звідси й без кнопки. Щоб
        // стрілка повернулась із сенсом, мостові потрібен вихід; доти порожній
        // перелік означає рівно те, що в назві тесту: кожен виклик має маршрут.
        "POST /messenger/files/carrier" to
            "Carrier v2 (C2): на телефоні ownCarrierLevel() = 0 (carrier/route.ts:13), тож веб тут v2 не обирає; нативний маршрут прийде з Carrier на телефоні",
        "DELETE /messenger/files/carrier/{id}/chunks/{id}" to
            "Carrier v2 (C6b): прибирання шматків невдалої передачі йде лише після POST вище, а його на телефоні не буває (ownCarrierLevel() = 0)",
        "GET /messenger/phone/invite" to
            "лише ПК: MeSections кличе його під !__PHONE_BUILD__ && !isPhoneShell(), телефон бере код із identity",
        "DELETE /messenger/chat/sessions/{id}" to
            "chatApi.deleteSession лежить у бандлі, але жоден екран його не кличе (сесії асистента — ПК); видно з 23.10, коли сторож почав читати /chat/",
        "PUT /messenger/chat/sessions/{id}" to
            "chatApi.updateSession лежить у бандлі, але жоден екран його не кличе (сесії асистента — ПК); видно з 23.10, коли сторож почав читати /chat/",
    )

    /**
     * 23.10: сторож читав лише `/messenger/`, а веб із train30 кличе й `/support/…`
     * (скарги, звернення). Міст їх не мав — 404, і черга на A52 росла вічно, поки
     * поїзди лишались зеленими. Тепер читається КОЖЕН виклик; префікс, якого міст
     * свідомо не обслуговує, мусить бути названий тут із причиною, а новий
     * непойменований префікс — червоне.
     */
    private val notOnPhone: Map<String, String> = mapOf(
        "auth" to "вхід вузла ПК (PIN/RFID/сесія); на телефоні особа — ключ пристрою, міст відповідає 404",
        "settings" to "налаштування вузла ПК; телефон тримає свої локально, міст відповідає 404",
        "pair" to "QR-спарування показує ПК (AddOwnDevice); телефон сканує камерою, міст відповідає 404",
        "context" to "стан і контекст вузла ПК; на телефоні його нема, міст відповідає 404",
        "voice" to "голос вузла ПК; телефон розпізнає через /messenger/voice/transcribe",
    )

    /** Ті самі переписування, що й `WebMessengerBridge.route`. */
    private fun bridgePath(path: String): String {
        val raw = path.trim('/').split('/')
        return when {
            raw[0] == "chat" || raw[0] == "agency" -> "/messenger/" + raw.joinToString("/")
            raw.size >= 2 && raw[0] == "api" && raw[1] == "v1" -> "/messenger/" + raw.drop(2).joinToString("/")
            else -> path
        }
    }

    private val backtickCall =
        Regex("\\(\\s*\"(GET|POST|PATCH|PUT|DELETE)\"\\s*,\\s*`([^`]*)`")

    private val quotedCall =
        Regex("\\(\\s*\"(GET|POST|PATCH|PUT|DELETE)\"\\s*,\\s*\"([^\"]*)\"")

    /** `${...}` будь-якої вкладеності на один рівень — це один сегмент-змінна. */
    private val template = Regex("\\$\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}")

    private val segLiteral = Regex("seg\\[(\\d+)\\]\\s*==\\s*\"([^\"]+)\"")
    private val segOptional = Regex("seg\\.getOrNull\\((\\d+)\\)\\s*==\\s*\"([^\"]+)\"")
    private val segSize = Regex("seg\\.size\\s*==\\s*(\\d+)")
    private val methodLiteral = Regex("method\\s*==\\s*\"([A-Z]+)\"")

    private fun callsInBundle(): Set<Call> {
        val scripts = bundle.walkTopDown().filter { it.isFile && it.name.endsWith(".js") }.toList()
        check(scripts.size > 1) { "у бандлі ${bundle.path} нема скриптів — сторож нічого не прочитав" }
        val out = LinkedHashSet<Call>()
        for (file in scripts) {
            val text = file.readText()
            for (regex in listOf(backtickCall, quotedCall)) {
                for (m in regex.findAll(text)) {
                    val raw = m.groupValues[2]
                    if (!raw.startsWith("/")) continue
                    val head = raw.trimStart('/').substringBefore('/').substringBefore('?').substringBefore('$')
                    if (head in notOnPhone) continue
                    val path = bridgePath(
                        template.replace(raw) { constantIn(text, it.value) ?: "{id}" }
                            .substringBefore('?').trimEnd('/'),
                    )
                    out += Call(m.groupValues[1], path)
                }
            }
        }
        return out
    }

    /**
     * `${Mn}`, де `Mn` у тому ж скрипті звʼязане рівно раз і з рядком, — це
     * той рядок. Без цього `importSavedNotes` виглядав би як імпорт у БУДЬ-ЯКУ
     * розмову, а міст його дає лише «Збереженому».
     */
    private fun constantIn(script: String, templ: String): String? {
        val name = templ.removePrefix("\${").removeSuffix("}").trim()
        if (!Regex("[A-Za-z_$][\\w$]*").matches(name)) return null
        val q = Regex.escape(name)
        if (Regex("(?<![\\w$.])$q=(?!=)").findAll(script).count() != 1) return null
        return Regex("(?<![\\w$.])$q=\"([\\w.-]+)\"").find(script)?.groupValues?.get(1)
    }

    /**
     * Перехоплення до таблиці: [WebMessengerBridge] віддає свої шляхи
     * [WebMessengerSaved] раніше за будь-яку гілку, а той розбирає `tail`, не
     * `seg`. Тут його гілки перекладаються в мову `seg` і склеюються з
     * `claims` — тож затверджений шлях обслуговує лише Saved, а загальні гілки
     * мосту його вже не бачать.
     */
    private class Intercept(val prefix: List<Pair<Int, String>>, val branches: List<String>)

    private fun savedIntercept(): Intercept? {
        val bridge = File(sources, "WebMessengerBridge.kt").readText()
        if (!bridge.contains("if (WebMessengerSaved.claims(seg)) return savedRoute(")) return null
        val saved = File(sources, "WebMessengerSaved.kt").readText()
        val id = Regex("const val ID = \"([^\"]+)\"").find(
            File(repoRoot(), "app/src/main/java/local/phantom/companion/data/SavedNotes.kt").readText(),
        )?.groupValues?.get(1) ?: error("SavedNotes.ID не знайдено — сторож не бачить імені «Збереженого»")
        val claims = Regex("fun claims\\(seg: List<String>\\): Boolean =\\s*(.+)").find(saved)
            ?.groupValues?.get(1)?.replace("SavedNotes.ID", "\"$id\"")
            ?: error("WebMessengerSaved.claims не знайдено")
        val prefix = segLiteral.findAll(claims).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
        check(prefix.isNotEmpty()) { "claims без літералів: $claims" }
        val off = Regex("val tail = seg\\.drop\\((\\d+)\\)").find(saved)?.groupValues?.get(1)?.toInt()
            ?: error("WebMessengerSaved більше не розбирає tail = seg.drop(n) — перечитай і навчи сторожа")
        val branches = saved.lineSequence()
            .filter { it.contains("->") && it.contains("tail") }
            .filterNot { it.substringAfter("->").trim().startsWith("Reply.Refused") }
            .map { tailToSeg(it.substringBefore("->"), off) + " && " + claims }
            .toList()
        return Intercept(prefix, branches)
    }

    private fun tailToSeg(condition: String, off: Int): String {
        val out = condition
            .replace(Regex("tail == listOf\\(([^)]*)\\)")) { m ->
                val parts = Regex("\"([^\"]*)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
                (listOf("seg.size == ${off + parts.size}") +
                    parts.mapIndexed { i, v -> "seg[${off + i}] == \"$v\"" }).joinToString(" && ")
            }
            .replace("tail.isEmpty()", "seg.size == $off")
            .replace(Regex("tail\\.size == (\\d+)")) { "seg.size == ${off + it.groupValues[1].toInt()}" }
            .replace(Regex("tail\\[(\\d+)\\]")) { "seg[${off + it.groupValues[1].toInt()}]" }
        check(!out.contains("tail")) { "сторож не розуміє гілки WebMessengerSaved: $condition" }
        return out
    }

    private fun routeFor(call: Call, branches: List<String>, intercept: Intercept?): String? {
        val segs = call.path.trim('/').split('/')
        val claimed = intercept != null &&
            intercept.prefix.all { (i, v) -> i < segs.size && segs[i] == v }
        return serves(call, if (claimed) intercept!!.branches else branches)
    }

    // Маршрут-клас, який міст питає `xRoute.claims(seg)`, сам сегмент у гілках не
    // повторює (`seg.size == 2 && method == "GET"`). Без префікса з `claims` така
    // гілка не має літерала, і сторож рахував живі intents/backpack/relayfs за 404.
    private fun claimedSegment(text: String): String? {
        val claims = Regex("fun claims\\(seg: List<String>\\): Boolean =\\s*(.+)").find(text)
            ?.groupValues?.get(1) ?: return null
        val constant = Regex("const val SEGMENT = \"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val resolved = if (constant != null) claims.replace("SEGMENT", "\"$constant\"") else claims
        return segLiteral.findAll(resolved).firstOrNull { it.groupValues[1] == "1" }?.groupValues?.get(2)
    }

    // Маршрут-клас із `claims(method, seg)` (WebMessengerPlaces) тримає всю умову
    // маршруту в самому `claims`: гілок `->` у нього немає, тож ця умова й є гілкою.
    private fun wholeClaims(text: String): String? {
        val claims = Regex("fun claims\\(method: String, seg: List<String>\\): Boolean =\\s*(.+)").find(text)
            ?.groupValues?.get(1) ?: return null
        val constant = Regex("const val SEGMENT = \"([^\"]+)\"").find(text)?.groupValues?.get(1)
        return if (constant != null) claims.replace("SEGMENT", "\"$constant\"") else claims
    }

    // Маршрут поза /messenger минає 404 мосту лише тоді, коли міст сам питає його
    // `claims(seg)`; без цього виклику його гілки — мертвий код, не маршрут.
    private fun outsideMessengerUnwired(text: String, bridge: String): Boolean {
        val claims = Regex("fun claims\\(seg: List<String>\\): Boolean =\\s*(.+)").find(text)?.groupValues?.get(1)
            ?: return false
        val constant = Regex("const val SEGMENT = \"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val resolved = if (constant != null) claims.replace("SEGMENT", "\"$constant\"") else claims
        val first = segLiteral.findAll(resolved).firstOrNull { it.groupValues[1] == "0" }?.groupValues?.get(2)
        if (first == null || first == "messenger") return false
        val name = Regex("class (\\w+)").find(text)?.groupValues?.get(1) ?: return true
        val props = Regex("val (\\w+) by lazy \\{\\s*$name\\(").findAll(bridge).map { it.groupValues[1] }
        val gate = bridge.indexOf("seg[0] != \"messenger\") return fail(404")
        return props.none {
            val at = bridge.indexOf("if ($it.claims(seg)) return $it.handle(")
            at >= 0 && (gate < 0 || at < gate)
        }
    }

    private fun branchesInBridge(): List<String> {
        val bridge = File(sources, "WebMessengerBridge.kt").readText()
        return sources.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .flatMap { file ->
                val text = file.readText()
                if (file.name != "WebMessengerBridge.kt" && outsideMessengerUnwired(text, bridge)) return@flatMap emptyList()
                val prefix = claimedSegment(text)?.let { " && seg[1] == \"$it\"" }.orEmpty()
                val consts = Regex("const val (\\w+) = \"([^\"]+)\"").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
                val arms = text.lineSequence()
                    .filter { it.contains("->") && it.contains("seg") }
                    .map { line ->
                        val arm = consts.entries.fold(line.substringBefore("->")) { a, (k, v) -> a.replace(Regex("\\b$k\\b"), "\"$v\"") }
                        // Гілка з власним seg[1] (hush-rules у WebMessengerHush) — окремий маршрут того ж класу, не підшлях claims.
                        if (Regex("seg\\[1\\]\\s*==\\s*\"").containsMatchIn(arm)) arm else arm + prefix
                    }
                    .toList()
                val constant = Regex("const val SEGMENT = \"([^\"]+)\"").find(text)?.groupValues?.get(1)
                val bare = if (arms.isEmpty()) {
                    Regex("fun claims\\(seg: List<String>\\): Boolean =\\s*(.+)").find(text)?.groupValues?.get(1)
                        ?.let { if (constant != null) it.replace("SEGMENT", "\"$constant\"") else it }
                        ?.takeIf { Regex("seg\\.size == \\d+").containsMatchIn(it) && !Regex("== [A-Za-z]").containsMatchIn(it) }
                } else null
                arms + listOfNotNull(wholeClaims(text), bare)
            }
            .toList()
    }

    /**
     * Гілка, яка цей виклик обслужить, або null.
     *
     * Гілка без ЖОДНОГО літерала сегмента пропускається: `else ->` і розбір
     * запиту інакше вдавали б маршрут на будь-що, і дзеркало стало б зеленим,
     * яке не вміє почервоніти.
     */
    private fun serves(call: Call, branches: List<String>): String? {
        val segs = call.path.trim('/').split('/')
        for (condition in branches) {
            val literals = (segLiteral.findAll(condition) + segOptional.findAll(condition))
                .map { it.groupValues[1].toInt() to it.groupValues[2] }
                .toList()
            if (literals.isEmpty()) continue
            val size = segSize.find(condition)?.groupValues?.get(1)?.toInt()
            if (size != null && size != segs.size) continue
            val methods = methodLiteral.findAll(condition).map { it.groupValues[1] }.toList()
            if (methods.isNotEmpty() && call.method !in methods) continue
            if (literals.all { (i, v) -> i < segs.size && segs[i] == v }) return condition.trim()
        }
        return null
    }

    @Test
    fun `кожен виклик мосту з бандла телефона має маршрут`() {
        val calls = callsInBundle()
        check(calls.size > 20) {
            "у бандлі знайдено лише ${calls.size} викликів мосту — сторож нічого не прочитав"
        }
        val branches = branchesInBridge()
        check(branches.size > 20) {
            "у джерелі мосту знайдено лише ${branches.size} гілок — сторож нічого не прочитав"
        }
        check(notOnPhone.values.none { it.isBlank() }) { "префікс без причини" }
        check(knownUnserved.values.none { it.isBlank() }) {
            "виняток без причини — наступний читач прийме його за недогляд"
        }

        val saved = savedIntercept()
        checkNotNull(saved) { "міст більше не віддає «Збережене» WebMessengerSaved першим — перечитай сторожа" }
        check(saved.branches.size >= 5) { "у WebMessengerSaved знайдено лише ${saved.branches.size} гілок" }
        val unserved = calls.filter { routeFor(it, branches, saved) == null }.map { it.toString() }.sorted()
        assertEquals(
            "веб кличе маршрути, яких у мості немає (${unserved.size}):\n    " +
                unserved.joinToString("\n    "),
            knownUnserved.keys.sorted(),
            unserved,
        )
    }

    /**
     * ХТО ЧИТАЄ ТЕ, ЩО МІСТ ПИШЕ.
     *
     * Маршрут, чиї поля нікому не потрібні, — це той самий 404, тільки зелений:
     * міст відповідає, веб не змінює жодного пікселя. Тож правка й відповідь
     * доводяться з боку ЧИТАЧА — у бандлі, а не в мості.
     */
    @Test
    fun `поля правки й відповіді мають читача в бандлі`() {
        val scripts = bundle.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".js") }
            .map { it.readText() }
            .toList()
        check(scripts.size > 1) { "у бандлі нема скриптів — сторож нічого не прочитав" }

        val readers = mapOf(
            // Веб робить із цього поля позначку «відредаговано»: isEdited:!!e.edited_at.
            "edited_at" to scripts.count { it.contains("edited_at") },
            // Адресат відповіді, який міст досі викидав по дорозі туди.
            "reply_to_id" to scripts.count { it.contains("reply_to_id") },
        )
        val unread = readers.filterValues { it == 0 }.keys.sorted()
        assertEquals(
            "міст пише поля, яких у бандлі не читає ніхто: $unread",
            emptyList<String>(),
            unread,
        )

        val source = File(sources, "WebMessengerBridge.kt").readText()
        check(source.contains("editedAtMs.takeIf")) {
            "міст більше не віддає edited_at виміряним — читач у бандлі лишився без даних"
        }
        check(source.contains("replyWithin(peerId")) {
            "міст більше не переносить reply_to_id у лист — відповіді знову зникають мовчки"
        }
    }

    @Test
    fun `дзеркало вміє почервоніти`() {
        val branches = branchesInBridge()
        // Вигаданого маршруту не буває — інакше попередній тест зелений завжди.
        assertEquals(
            null,
            serves(Call("PATCH", "/messenger/conversations/{id}/такого-нема"), branches),
        )
        // А справжній — знаходиться, інакше він зелений з іншої причини: сліпий.
        assertNotNull(
            serves(Call("DELETE", "/messenger/conversations/{id}/messages/{id}"), branches),
        )
        assertNotNull(
            serves(Call("PATCH", "/messenger/conversations/{id}/messages/{id}"), branches),
        )
        // /support/* (скарги, звернення, збій) — на сервер через міст, не 404.
        assertNotNull(serves(Call("GET", "/support/reports"), branches))
        assertNotNull(serves(Call("POST", "/support/reports/{id}/appeal"), branches))
        assertNotNull(serves(Call("POST", "/support/tickets/{id}/messages"), branches))
        assertNotNull(serves(Call("POST", "/support/crash"), branches))
        assertEquals(null, serves(Call("DELETE", "/support/reports"), branches))
        assertEquals(null, serves(Call("POST", "/support/такого-нема"), branches))
        val saved = savedIntercept()
        // Імпорт є лише в «Збереженого»: якщо веб колись покличе його на чужу
        // розмову, це дефект вебу, і сторож мусить це сказати.
        assertEquals(null, routeFor(Call("POST", "/messenger/conversations/{id}/import"), branches, saved))
        assertNotNull(routeFor(Call("POST", "/messenger/conversations/chat_saved_notes/import"), branches, saved))
        assertEquals(null, routeFor(Call("GET", "/messenger/conversations/chat_saved_notes/import"), branches, saved))
        // Перехоплений шлях загальні гілки мосту вже не рятують.
        assertEquals(null, routeFor(Call("POST", "/messenger/conversations/chat_saved_notes/такого-нема"), branches, saved))
        // Без константи в тому ж скрипті шлях лишається змінною — не вгадується.
        val script = "const Mn=\"chat_saved_notes\";let e=1;e=2;"
        assertEquals("chat_saved_notes", constantIn(script, "\${Mn}"))
        assertEquals(null, constantIn(script, "\${e}"))
        assertEquals(null, constantIn("$script Mn=3;", "\${Mn}"))
    }
}
