package local.phantom.companion.core.net.map

/**
 * Розбір тегового селектора — ОДНЕ оголошення граматики на онлайн і офлайн.
 *
 * Overpass приймає в одних дужках рівно один ключ. Тому `|` між КЛЮЧАМИ —
 * не «або»: на `[shelter_type=bomb_shelter|bunker_type=bomb_shelter]` сервер
 * відповідає HTTP 400 `parse error: ']' expected - '|' found`. Помилка не
 * лишається помилкою — вона доїжджає до скла як «Джерело мовчить», тобто
 * невідрізненно від обриву мережі, і саме тому мертвий чіп «Укриття» ніхто
 * не помітив. Розбитий на клаузи, той самий рядок стає справжнім «або».
 *
 * `|` усередині лапок (`amenity~"cafe|restaurant"`) — це вже регулярний вираз
 * на ЗНАЧЕННІ, і його чіпати не можна.
 */
object TagSelector {

    /** [regex] — `key~"a|b"`; інакше дослівна рівність. [values] порожній = «ключ присутній». */
    data class Clause(val key: String, val values: Set<String>, val regex: Boolean) {
        private val compiled: Regex? = if (regex) runCatching { Regex(values.joinToString("|")) }.getOrNull() else null

        fun matches(value: String?): Boolean {
            if (value == null) return false
            if (values.isEmpty()) return true
            return if (regex) compiled?.containsMatchIn(value) ?: false else value in values
        }
    }

    /** Клаузи «або-або». Порожній список = селектор нерозбірний, і це не «збігається з усім». */
    fun clauses(selector: String): List<Clause> = parts(selector).mapNotNull(::parseClause)

    fun matches(clauses: List<Clause>, tags: Map<String, String>): Boolean =
        clauses.any { it.matches(tags[it.key]) }

    /**
     * Те саме, але з підстраховкою на пакетні поля `cat`/`sub`: місця, збережені
     * до того, як пак почав нести повну мапу тегів, мають лише їх.
     */
    fun matches(clauses: List<Clause>, tags: Map<String, String>, cat: String?, sub: String?): Boolean =
        clauses.any { c -> c.matches(tags[c.key]) || (c.key == cat && c.matches(sub)) }

    /** `|` поза лапками й поза `[...]` розділяє клаузи; кожна придатна для власних дужок Overpass. */
    fun parts(selector: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var quoted = false
        var depth = 0
        for (ch in selector) {
            when {
                ch == '"' -> { quoted = !quoted; buf.append(ch) }
                quoted -> buf.append(ch)
                ch == '[' -> { depth++; buf.append(ch) }
                ch == ']' -> { depth--; buf.append(ch) }
                ch == '|' && depth == 0 -> { out += buf.toString(); buf.setLength(0) }
                else -> buf.append(ch)
            }
        }
        out += buf.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseClause(raw: String): Clause? {
        val s = raw.trim()
        val tilde = s.indexOf('~')
        if (tilde > 0) {
            val key = s.substring(0, tilde).trim()
            val pattern = s.substring(tilde + 1).trim().trim('"')
            return if (key.isNotEmpty() && pattern.isNotEmpty()) Clause(key, setOf(pattern), regex = true) else null
        }
        val eq = s.indexOf('=')
        if (eq > 0) {
            val key = s.substring(0, eq).trim().removeSuffix("!")
            val value = s.substring(eq + 1).trim().trim('"')
            return if (key.isNotEmpty() && value.isNotEmpty()) Clause(key, setOf(value), regex = false) else null
        }
        // Голий ключ — «тег присутній», як `[entrance]` в Overpass.
        return if (s.isNotEmpty() && s.none { it in "=~[]\" " }) Clause(s, emptySet(), regex = false) else null
    }
}
