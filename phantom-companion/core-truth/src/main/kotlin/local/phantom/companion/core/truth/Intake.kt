package local.phantom.companion.core.truth

data class Submission(
    val kindWire: String,
    val readingWire: String,
    val place: Place?,
    val note: String? = null,
)

enum class Refusal(val uk: String, val en: String) {
    UNKNOWN_KIND("Такого виду фактів немає.", "No such kind of fact."),
    BAD_READING("Такого стану для цього факту немає.", "No such state for this fact."),
    NO_PLACE("Факт без місця нічого не каже.", "A fact without a place says nothing."),
    NOTE_TOO_LONG("Примітка задовга.", "The note is too long."),
    FORBIDDEN_CATEGORY(
        "Про військових, ППО, влучання й рух техніки повідомляти заборонено законом воєнного стану — це видає їх ворогу.",
        "Reporting troops, air defence, strikes or vehicle movements is banned under martial law — it gives them away.",
    ),
    FORBIDDEN_TEXT(
        "У примітці є те, що заборонено публікувати під час воєнного стану. Прибери її або надішли без примітки.",
        "The note mentions something banned from publication under martial law. Remove it or send without a note.",
    ),
}

sealed interface Intake {
    /** [noteForModeration] іде в чергу людині-модератору; у публічний факт вона не потрапляє ніколи. */
    data class Accepted(val kind: FactKind, val reading: Reading, val place: Place, val noteForModeration: String?) : Intake
    data class Refused(val reason: Refusal) : Intake
}

/**
 * Перша лінія — закритий словник: військового виду немає в [FactKind], тож його
 * неможливо висловити. Друга — вільний текст не публікується взагалі, лише йде
 * на модерацію. Третя — основи слів нижче; вони ловлять необережного, а не
 * того, хто навмисно пише «п.п.о» латиницею з помилками — від нього рятує друга.
 */
object IntakeGate {
    const val NOTE_MAX = 280

    private val FORBIDDEN_KIND_ROOTS = listOf(
        "military", "army", "troop", "air_defen", "air-defen", "airdefen", "strike", "impact", "explosion",
        "shelling", "checkpoint", "vehicle", "weapon", "drone", "missile", "artillery", "tcc",
    )

    // Абревіатури — лише цілим словом: «всу» як початок ловило б «всупереч».
    private val FORBIDDEN_ACRONYMS = setOf(
        "ппо", "пво", "зрк", "бпла", "зсу", "всу", "тцк", "бтр", "бмп", "рсзв", "дрг", "ппошник",
    )

    private val FORBIDDEN_STEMS = listOf(
        "зенітн", "дрон", "шахед", "шахід", "герань", "ракет",
        "приліт", "прильот", "прилет", "влучан", "влучил", "влучив", "попадан", "вибух", "взрив", "взрыв",
        "уламк", "обломк", "обстріл", "обстрел",
        "військ", "войск", "военн", "солдат", "військкомат", "воєнкомат", "военкомат",
        "позиці", "позици", "окоп", "бліндаж", "блиндаж", "блокпост", "блокпіст",
        "танк", "артилер", "гармат", "міномет", "миномет", "хаймарс",
        "troop", "military", "soldier", "airdefen", "missile", "drone", "shahed", "strike", "explosion",
        "artillery", "tank", "checkpoint", "himars",
    )

    // Латиниця, що виглядає кирилицею: «ппo» з латинською o інакше пройшло б.
    private val LOOKALIKE = mapOf(
        'a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с', 'x' to 'х', 'y' to 'у',
        'i' to 'і', 'k' to 'к', 'm' to 'м', 't' to 'т', 'h' to 'н', 'b' to 'в',
    )

    fun check(s: Submission): Intake {
        val kindWire = s.kindWire.trim().lowercase()
        if (FORBIDDEN_KIND_ROOTS.any { it in kindWire } || forbiddenText(kindWire)) return Intake.Refused(Refusal.FORBIDDEN_CATEGORY)
        val kind = FactKind.byWire(kindWire) ?: return Intake.Refused(Refusal.UNKNOWN_KIND)
        val reading = kind.readings.firstOrNull { it.wire == s.readingWire.trim().lowercase() }
            ?: return Intake.Refused(Refusal.BAD_READING)
        val place = s.place?.takeIf { it.cell.value.isNotBlank() } ?: return Intake.Refused(Refusal.NO_PLACE)
        if (place.objectId != null && forbiddenText(place.objectId)) return Intake.Refused(Refusal.FORBIDDEN_TEXT)
        val note = s.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null) {
            if (note.length > NOTE_MAX) return Intake.Refused(Refusal.NOTE_TOO_LONG)
            if (forbiddenText(note)) return Intake.Refused(Refusal.FORBIDDEN_TEXT)
        }
        return Intake.Accepted(kind, reading, place, note)
    }

    fun forbiddenText(text: String): Boolean {
        val lower = text.lowercase().replace('ё', 'е').replace('\'', 'ʼ').replace('’', 'ʼ')
        val latinTokens = tokens(lower)
        val cyrTokens = tokens(lower.map { LOOKALIKE[it] ?: it }.joinToString(""))
        return listOf(latinTokens, cyrTokens).any { toks ->
            joinSpelledOut(toks).any { tok -> tok in FORBIDDEN_ACRONYMS || FORBIDDEN_STEMS.any { tok.startsWith(it) } } ||
                toks.zipWithNext { a, b -> a + b }.any { it == "airdefense" || it == "airdefence" }
        }
    }

    // `\b` у регулярних виразах Java бачить лише ASCII-слова, тому межі шукаємо самі.
    private fun tokens(s: String): List<String> =
        s.splitWhere { !it.isLetter() && it != '\'' }.map { it.replace("'", "") }.filter { it.isNotEmpty() }

    private fun String.splitWhere(isSep: (Char) -> Boolean): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        for (ch in this) if (isSep(ch)) { out += sb.toString(); sb.clear() } else sb.append(ch)
        out += sb.toString()
        return out
    }

    /** «п п о» та «п.п.о» — окремі літери зшиваються в одне слово. */
    private fun joinSpelledOut(toks: List<String>): List<String> {
        val out = ArrayList<String>(toks)
        val run = StringBuilder()
        for (t in toks + "") {
            if (t.length == 1) run.append(t) else { if (run.length > 1) out += run.toString(); run.clear() }
        }
        return out
    }
}

data class AlertWindow(val startMs: Long, val endMs: Long?)

/**
 * Світло, вода, звʼязок і дороги, що змінились під час тривоги, публікуються
 * лише через годину після її відбою: «пів району без світла за хвилину після
 * вибуху» — це координата влучання, навіть якщо про влучання ніхто не писав.
 * Тривогу для комірки визначає той, хто викликає.
 */
object Embargo {
    const val AFTER_ALERT_MS = 60 * 60_000L

    fun releaseAtMs(obs: Observation, alerts: List<AlertWindow>): Long {
        if (!obs.key.kind.embargoDuringAlert) return obs.atMs
        val hit = alerts.filter { obs.atMs >= it.startMs && (it.endMs == null || obs.atMs <= it.endMs) }
        if (hit.isEmpty()) return obs.atMs
        if (hit.any { it.endMs == null }) return Long.MAX_VALUE
        return hit.maxOf { it.endMs!! } + AFTER_ALERT_MS
    }

    fun publishable(observations: List<Observation>, alerts: List<AlertWindow>, nowMs: Long): List<Observation> =
        observations.filter { releaseAtMs(it, alerts) <= nowMs }
}
