package local.phantom.companion.ui.messenger

/**
 * Картки композера, розібрані для веба.
 *
 * Тринадцять родів приїжджали в веб родом «інше» з ПОРОЖНІМ тілом: людина
 * бачила рамку з написом «Опитування» і жодного варіанта. Це та сама вада, що
 * й голосове без звуку — вигляд спроможності без спроможності.
 *
 * Тут кожен рід отримує один із двох кінців, і третього немає:
 *  * [Read.Card] — вміст доїжджає до самої картки, яку веб УЖЕ вміє малювати;
 *  * [Read.Words] — картки не буде, буде чесне речення про те, що є насправді.
 *
 * Перелік малювальних родів звірено з самим бандлом, а не з наміром: гілка
 * `type==="..."` існує для восьми (table, chart, task-list, multi-quote, poll,
 * split-bill, code, event). Дріт події лишається текстом `PH-EVENT:`, тож
 * старий застосунок співрозмовника читає його словами, як і раніше.
 *
 * Відмови тут ті самі, що на рідному екрані ([PeerMessageCards]): немає
 * питання — немає опитування, немає суми — немає рахунку. Картка, зібрана з
 * підставлених значень, читається як домовленість, якої ніхто не робив.
 */
internal object WebMessengerCards {

    const val POLL = "poll"
    const val TABLE = "table"
    const val TASKS = "task-list"
    const val BILL = "split-bill"
    const val CHART = "chart"
    const val QUOTES = "multi-quote"
    const val CODE = "code"
    const val EVENT = "event"

    /** Чесні речення замість карток. Рядок ресурсу вибирає міст. */
    const val SAY_VOICE = "voice"
    const val SAY_EVENT = "event"
    const val SAY_BODILESS = "bodiless"
    const val SAY_EMPTY = "empty"
    const val SAY_UNKNOWN = "unknown"
    const val SAY_SOLANA = "solana"

    sealed interface Read {
        data class Card(val kind: String, val payload: Map<String, Any?>) : Read

        data class Words(val code: String, val args: List<String>) : Read

        /** Позначка є, але показувати нема чого й нема про що казати. */
        data object Silent : Read
    }

    private const val MARK = "PH-"

    fun of(text: String): Read? {
        val clean = text.trim()
        if (!clean.startsWith(MARK)) return null
        val mark = clean.substringBefore(':', missingDelimiterValue = "")
        val data = clean.substringAfter(':', missingDelimiterValue = "")
        // Позначка знайома, а вміст непридатний — це НЕ «не вміємо показати»:
        // вміємо, показувати нічого. Зливати ці два випадки в одне речення
        // означало б наговорити на збірку те, чого немає.
        fun said(read: Read?): Read = read ?: Read.Words(SAY_EMPTY, listOf(mark))
        return when (mark) {
            "PH-POLL" -> said(poll(data))
            "PH-TABLE" -> said(table(data))
            "PH-TASKLIST" -> said(tasks(data))
            "PH-BILL" -> said(bill(data))
            "PH-CHART" -> said(chart(data))
            "PH-MULTIQUOTE" -> said(quotes(data))
            "PH-CODE" -> said(code(data))
            "PH-EVENT" -> said(event(data))
            "PH-VOICE" -> voice(data)
            // Імʼя й вага без жодного байта. Назву сюди не переносимо: її
            // склала знесена театральна гілка, і повторити її означало б
            // видати вигадку за надіслане.
            "PH-IMAGE", "PH-FILE" -> Read.Words(SAY_BODILESS, emptyList())
            // Мітка групового ключа. Машині потрібна, людині — ні.
            "PH-KEY" -> Read.Silent
            else -> null
        }
    }

    /**
     * Позначка, якої цей перелік не знає.
     *
     * Восьмий рід зʼявиться, і без цієї гілки він поїхав би або сирим дротом,
     * або порожньою карткою. Чесна відмова першою, спроможність потім.
     */
    fun unknown(mark: String): Read.Words = Read.Words(SAY_UNKNOWN, listOf(mark))

    // ── роди, що доїжджають карткою ──────────────────────────────────────

    private fun poll(data: String): Read? {
        val f = data.split(';')
        val question = f.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val options = f.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
        if (options.isEmpty()) return null
        return Read.Card(
            POLL,
            mapOf(
                "question" to question,
                "options" to options.mapIndexed { i, o ->
                    // `voters` мусить бути переліком завжди: веб кличе
                    // `voters.includes(...)` без перевірки на null.
                    mapOf("id" to "opt_$i", "text" to o, "votes" to 0, "voters" to emptyList<String>())
                },
            ),
        )
    }

    private fun table(data: String): Read? {
        val f = data.split(';')
        val columns = f.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (columns.isEmpty()) return null
        val rows = f.drop(2).filterNot { it.startsWith(SUMMARY) }
            .map { row -> row.split(',').map { it.trim() } }
            .filter { it.any { cell -> cell.isNotEmpty() } }
        if (rows.isEmpty()) return null
        val keys = columns.indices.map { "c$it" }
        return Read.Card(
            TABLE,
            mapOf(
                "title" to f.getOrNull(0).orEmpty().trim(),
                "columns" to columns.mapIndexed { i, label ->
                    mapOf("key" to keys[i], "label" to label, "type" to "text")
                },
                "rows" to rows.map { cells ->
                    keys.withIndex().associate { (i, key) -> key to cells.getOrElse(i) { "" } }
                },
            ),
        )
    }

    private fun tasks(data: String): Read? {
        val f = data.split(';')
        val tasks = f.drop(1).map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, raw ->
            val p = raw.split(':')
            mapOf(
                "id" to "task_$i",
                "title" to (p.getOrNull(0)?.trim().orEmpty().ifEmpty { raw }),
                "completed" to (p.getOrNull(1)?.trim() in DONE),
                "assigneeName" to p.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        if (tasks.isEmpty()) return null
        return Read.Card(TASKS, mapOf("title" to f.getOrNull(0).orEmpty().trim(), "tasks" to tasks))
    }

    private fun bill(data: String): Read? {
        val f = data.split(';')
        val rawTotal = f.getOrNull(1)?.trim().orEmpty()
        val match = BILL_TOTAL.matchEntire(rawTotal) ?: return null
        val total = match.groupValues[1].toDoubleOrNull() ?: return null
        val code = match.groupValues[2]
        val people = f.drop(2).mapIndexedNotNull { i, raw ->
            val p = raw.split(':')
            val share = p.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            mapOf(
                "id" to "p_$i",
                "name" to (p.getOrNull(0)?.trim().orEmpty()),
                "share" to share,
                "paid" to (p.getOrNull(2)?.trim() in DONE),
            )
        }
        if (people.isEmpty()) return null
        return Read.Card(
            BILL,
            mapOf(
                "title" to f.getOrNull(0).orEmpty().trim(),
                "totalAmount" to total,
                "currency" to code,
                "participants" to people,
            ),
        )
    }

    private fun chart(data: String): Read? {
        val f = data.split(';')
        val points = f.getOrNull(1)?.split(',').orEmpty().mapNotNull { raw ->
            val p = raw.split(':')
            val label = p.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Крапка без числа не стає нулем і не стає серединою шкали: вона
            // просто не потрапляє на графік.
            val value = p.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            label to value
        }
        if (points.isEmpty()) return null
        return Read.Card(
            CHART,
            mapOf(
                "title" to f.getOrNull(0).orEmpty().trim(),
                "type" to "bar",
                // Вісь X у вебі читає саме "name" (`dataKey:"name"`).
                "data" to points.map { (label, value) -> mapOf("name" to label, "value" to value) },
                "keys" to listOf(mapOf("key" to "value", "label" to f.getOrNull(0).orEmpty().trim(), "color" to ACCENT)),
                "takeaway" to f.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private fun quotes(data: String): Read? {
        val f = data.split(';')
        val said = f.drop(1).filterNot { it.startsWith(CONCLUSION) }.map { it.trim() }.filter { it.isNotEmpty() }
        if (said.isEmpty()) return null
        val conclusion = f.firstOrNull { it.startsWith(CONCLUSION) }?.removePrefix(CONCLUSION)?.trim()
        return Read.Card(
            QUOTES,
            mapOf(
                "title" to f.getOrNull(0).orEmpty().trim(),
                "quotes" to said.mapIndexed { i, raw ->
                    val p = raw.split(':')
                    val hasAuthor = p.size > 1 && p[0].isNotBlank()
                    mapOf(
                        "id" to "q_$i",
                        "senderName" to if (hasAuthor) p[0].trim() else "",
                        "text" to if (hasAuthor) p.drop(1).joinToString(":").trim() else raw,
                    )
                },
                "synthesis" to conclusion?.let {
                    mapOf("keyPoints" to emptyList<String>(), "conclusion" to it)
                },
            ),
        )
    }

    private fun code(data: String): Read? {
        val f = data.split(';')
        // Крапка з комою всередині коду законна: усе після другого поля —
        // це код, склеєний назад так само, як його різали.
        val body = f.drop(2).joinToString(";")
        if (body.isBlank()) return null
        return Read.Card(
            CODE,
            mapOf(
                "title" to f.getOrNull(0).orEmpty().trim(),
                "language" to (f.getOrNull(1)?.trim().orEmpty()),
                "code" to body,
            ),
        )
    }

    /** Назва, день і час обовʼязкові: підставлені «18:30» читались би як домовленість. */
    private fun event(data: String): Read? {
        val f = data.split(';')
        val title = f.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val date = f.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val time = f.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Місце може бути порожнім чесно: «десь» — це відсутність даних, а не
        // вигадка, на відміну від підставлених дати й часу.
        return Read.Card(
            EVENT,
            mapOf(
                "title" to title,
                "date" to date,
                "time" to time,
                "location" to f.getOrNull(3)?.trim().orEmpty(),
            ),
        )
    }

    // ── роди, що доїжджають словами ──────────────────────────────────────

    private fun voice(data: String): Read {
        val seconds = data.substringBefore(';').trim().toIntOrNull() ?: 0
        return Read.Words(
            SAY_VOICE,
            listOf(String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60)),
        )
    }

    private val DONE = setOf("1", "true")
    private const val SUMMARY = "Всього:"
    private const val CONCLUSION = "Висновок:"
    private val BILL_TOTAL = Regex("""^([0-9.]+)\s*([A-Z]{3})?$""")
    private const val ACCENT = "#E87A42"
}
