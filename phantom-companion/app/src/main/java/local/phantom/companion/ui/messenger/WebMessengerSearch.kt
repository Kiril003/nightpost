package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.PaymentFrame

/**
 * Пошук по ВСІХ листах на апараті — для `GET /messenger/search` мосту.
 *
 * ЧОМУ НА ВУЗЛІ, А НЕ У ВЕБІ. Веб тримає лише ті стрічки, які людина вже
 * відкривала. Щоб шукати в решті, довелося б перевезти у WebView увесь журнал —
 * тобто другу, розшифровану копію кожної розмови в памʼяті вкладки (і третю,
 * якщо її ще й проіндексувати). Журнал уже лежить тут, у памʼяті процесу
 * ([local.phantom.companion.data.PeerMesh.outbox]), зашифрований на диску;
 * пошук по ньому не копіює нічого, а через міст їдуть лише знахідки — по
 * рядку на лист, не більше [MAX_LIMIT].
 *
 * ЩО ЗБІГАЄТЬСЯ — рівно те саме, що у вебі (`services/messageSearch.ts`):
 * підрядок після згортання 1:1 — регістр, апострофи, латинські двійники
 * кирилиці. ґ/г, і/и, є/е не зливаються. Вектори в обох тестах однакові.
 *
 * ШВИДКІСТЬ. Міст синхронний: поки тут рахується, JS чекає. Тому розбір листа
 * ([WebMessengerKinds.fields], розгортання конверта групи) робиться лише для
 * КАНДИДАТІВ: спершу згорнутий збіг шукається в сирому тілі без жодної
 * алокації, і лише лист, де він є (або опис вкладення), розбирається далі.
 *
 * Без org.json і без Android: вирок перевіряється звичайним JVM-тестом.
 */
object WebMessengerSearch {

    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 300

    /** Скільки знаків поля везти вебу: фрагмент він вирізає сам. */
    const val TEXT_CAP = 2000

    enum class Field(val wire: String) { TEXT("text"), CAPTION("caption"), FILE("file") }

    /**
     * Одна розмова, як її бачить веб.
     *
     * [unwrap] — що людина набрала в тілі: для розмови вдвох це саме тіло, для
     * групи — текст з-під конверта. null — рядок не є сказаним (дія над
     * складом, зіпсований конверт).
     */
    class Source(
        val conversationId: String,
        val lines: List<PeerMessage>,
        val unwrap: (String) -> String? = { it },
    )

    data class Hit(
        val conversationId: String,
        val messageId: String,
        val authorId: String,
        val field: Field,
        val text: String,
        val sentAtMs: Long,
    )

    data class Report(
        val hits: List<Hit>,
        val scanned: Int,
        val conversations: Int,
        val truncated: Boolean,
    )

    // ── згортання ────────────────────────────────────────────────────────

    private const val APOSTROPHES = "’ʼ‘`´′"

    /** Лише ті двійники, що однакові й у малому регістрі (див. веб). */
    private val LOOKALIKE = mapOf(
        'a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с',
        'x' to 'х', 'y' to 'у', 'i' to 'і', 'k' to 'к',
    )

    fun fold(ch: Char): Char {
        if (ch in APOSTROPHES) return '\''
        // «İ» у вебі в нижньому регістрі дає ДВА знаки, і веб лишає його як є.
        if (ch == '\u0130') return ch
        val low = ch.lowercaseChar()
        return LOOKALIKE[low] ?: low
    }

    fun fold(s: String): String {
        val out = CharArray(s.length)
        for (i in s.indices) out[i] = fold(s[i])
        return String(out)
    }

    /** Запит так, як його згортає веб: обрізаний, пробіли злиті в один. */
    fun foldQuery(raw: String): String = fold(raw.trim().replace(SPACES, " "))

    /** Позиція згорнутого [needle] у [hay] без згортання всього рядка. */
    fun indexOfFolded(hay: String, needle: String, from: Int = 0): Int {
        if (needle.isEmpty()) return -1
        val first = needle[0]
        val last = hay.length - needle.length
        var i = from
        while (i <= last) {
            if (fold(hay[i]) == first) {
                var j = 1
                while (j < needle.length && fold(hay[i + j]) == needle[j]) j++
                if (j == needle.length) return i
            }
            i++
        }
        return -1
    }

    // ── що з листа шукається ─────────────────────────────────────────────

    /**
     * Поля сказаного листа, у яких шукаємо: слова (зокрема слова моделі),
     * підпис і назва вкладення, а в рахунку, витратах, зустрічі й переказі —
     * рядок картки словами ([WebMessengerCardFacts]), як у вебі. Сирий JSON,
     * маршрути, службові речення — ні: це протокол, а не слова людини.
     */
    fun fields(said: String): List<Pair<Field, String>> {
        val f = WebMessengerKinds.fields(said)
        if (f[WebMessengerKinds.PAYMENT] != null) return WebMessengerCardFacts.lines(f).map { Field.TEXT to it }
        if (f[WebMessengerKinds.NOTICE] != null) return emptyList()
        return when (f[WebMessengerKinds.KIND]) {
            WebMessengerKinds.TEXT ->
                listOf(Field.TEXT to ((f[WebMessengerAi.SAID] as? String) ?: said))
            WebMessengerKinds.IMAGE, WebMessengerKinds.FILE -> {
                val att = PeerFile.describe(said) ?: return emptyList()
                buildList {
                    if (att.caption.isNotBlank()) add(Field.CAPTION to att.caption)
                    if (att.name.isNotBlank()) add(Field.FILE to att.name)
                }
            }
            else -> WebMessengerCardFacts.lines(f).map { Field.TEXT to it }
        }
    }

    private const val ATTACHMENT_MARK = "\"blob_id\""

    /** Тіло картки в base64, суми переказу в одиницях: збіг у сирому тілі їх не бачить. */
    private val FACT_MARKS = listOf("PH-CRD:", PaymentFrame.TAG)

    // ── сам пошук ────────────────────────────────────────────────────────

    fun search(query: String, sources: List<Source>, limit: Int = DEFAULT_LIMIT): Report {
        val needle = foldQuery(query)
        val cap = limit.coerceIn(1, MAX_LIMIT)
        var scanned = 0
        if (needle.isEmpty()) return Report(emptyList(), 0, sources.size, false)
        val found = ArrayList<Hit>()
        for (src in sources) {
            for (m in src.lines) {
                scanned++
                val body = m.body
                // Кандидат: згорнутий збіг у сирому тілі, опис вкладення
                // (назва й підпис у JSON можуть бути екрановані) або картка.
                if (indexOfFolded(body, needle) < 0 && !body.contains(ATTACHMENT_MARK) && FACT_MARKS.none(body::contains)) continue
                val said = src.unwrap(body) ?: continue
                val hit = fields(said).firstOrNull { (_, text) -> indexOfFolded(text, needle) >= 0 } ?: continue
                found += Hit(
                    conversationId = src.conversationId,
                    messageId = m.id,
                    authorId = m.fromId,
                    field = hit.first,
                    text = hit.second.take(TEXT_CAP),
                    sentAtMs = m.sentAtMs,
                )
            }
        }
        found.sortByDescending { it.sentAtMs }
        return Report(
            hits = found.take(cap),
            scanned = scanned,
            conversations = sources.size,
            truncated = found.size > cap,
        )
    }

    /** `q=…&limit=…` з рядка запиту мосту. */
    fun parse(query: String): Pair<String, Int> {
        var q = ""
        var limit = DEFAULT_LIMIT
        for (part in query.split('&')) {
            val key = part.substringBefore('=')
            val value = runCatching { java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }
                .getOrDefault("")
            when (key) {
                "q" -> q = value
                "limit" -> limit = value.toIntOrNull() ?: DEFAULT_LIMIT
            }
        }
        return q to limit
    }

    private val SPACES = Regex("\\s+")
}
