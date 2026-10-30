package local.phantom.companion.core.net.peer

/**
 * Правка й стирання «для всіх» власного рядка — кадром, який згортається в ціль.
 *
 * Не [PeerMark]: позначку ставлять на ЧУЖИЙ рядок і згортають у поле поруч, а
 * тут автор переписує СВІЙ рядок на місці, і прикладається це лише тоді, коли
 * підписаний відправник кадру і є автором цілі.
 *
 * Кадр лишається в журналі. Правка, що обігнала свій рядок, нічого не створює й
 * чекає: [apply] проходить журнал на кожному прибутті, і рядок, який приїде
 * пізніше, застане її там.
 */
object PeerRevise {

    internal const val EDIT = "PH-EDT:"
    internal const val UNSEND = "PH-UNS:"
    internal const val ORIGIN = "rev0-"

    private const val SEP = ';'

    /** Як у [PeerCard.MAX_CARD_CHARS]: base64 цього й конверт мусять влізти в дріт. */
    const val MAX_TEXT_BYTES = 18 * 1024

    /** Правка після 48 год від відправки не приймається — і в мене, і в отримувача. */
    const val EDIT_WINDOW_MS = 48L * 60 * 60 * 1000

    /** Обидві миті — з годинника автора: рядок і кадр правки ставить той самий апарат. */
    fun inWindow(line: PeerMessage, atMs: Long): Boolean = atMs - line.sentAtMs <= EDIT_WINDOW_MS

    sealed interface Act {
        val targetId: String
    }

    data class Edit(override val targetId: String, val text: String) : Act

    data class Unsend(override val targetId: String) : Act

    fun encode(act: Act): String? {
        if (!isRef(act.targetId)) return null
        return when (act) {
            is Unsend -> UNSEND + act.targetId
            is Edit -> {
                if (act.text.isBlank() || !isWords(act.text)) return null
                val raw = act.text.toByteArray(Charsets.UTF_8)
                if (raw.size > MAX_TEXT_BYTES) return null
                EDIT + act.targetId + SEP +
                    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
            }
        }
    }

    fun isEdit(body: String): Boolean = body.startsWith(EDIT)

    fun isUnsend(body: String): Boolean = body.startsWith(UNSEND)

    fun isRevise(body: String): Boolean = isEdit(body) || isUnsend(body)

    fun decode(body: String): Act? = when {
        isUnsend(body) -> body.removePrefix(UNSEND).takeIf(::isRef)?.let(::Unsend)
        isEdit(body) -> {
            val parts = body.removePrefix(EDIT).split(SEP)
            val raw = parts.takeIf { it.size == 2 && isRef(it[0]) }
                ?.let { runCatching { java.util.Base64.getUrlDecoder().decode(it[1]) }.getOrNull() }
            raw?.takeIf { it.size <= MAX_TEXT_BYTES }
                ?.let { String(it, Charsets.UTF_8) }
                ?.takeIf { it.isNotBlank() && isWords(it) }
                ?.let { Edit(parts[0], it) }
        }
        else -> null
    }

    /** Слова людини, а не тіло, складене пристроєм: інакше правка підробляла б рід. */
    fun editable(line: PeerMessage): Boolean =
        line.deletedAtMs == 0L && spoken(line) && isWords(said(line.body))

    /**
     * Підпис фото чи файла правиться тим самим кадром: збірка, що підписів не
     * правила, бачить у рядку не слова ([editable]) і кадр мовчки лишає.
     */
    fun captionable(line: PeerMessage): Boolean =
        line.deletedAtMs == 0L && spoken(line) &&
            PeerFile.describe(said(line.body))?.kind.let { it == PeerFile.IMAGE || it == PeerFile.FILE }

    fun unsendable(line: PeerMessage): Boolean =
        line.deletedAtMs == 0L && spoken(line) && !PeerGroup.isSystem(said(line.body))

    fun edited(line: PeerMessage, text: String, atMs: Long): PeerMessage =
        line.copy(body = rewrap(line.body, PeerFile.recaption(said(line.body), text) ?: text), editedAtMs = atMs)

    /** Конверт групи лишається: без нього рядок випав би з гілки, а не став надгробком. */
    fun tombstoned(line: PeerMessage, atMs: Long): PeerMessage =
        line.copy(body = rewrap(line.body, ""), deletedAtMs = atMs)

    data class Applied(val log: List<PeerMessage>, val unsent: List<PeerMessage>)

    /**
     * Чисте й ідемпотентне: та сама купа завжди дає той самий журнал.
     *
     * Порядок правок — годинник АВТОРА ([PeerMessage.sentAtMs] кадру), потім id;
     * тому дві правки, що доїхали навпаки, лягають однаково на обох апаратах.
     * Стирання перемагає будь-яку правку незалежно від часу.
     */
    fun apply(log: List<PeerMessage>): Applied {
        val acts = log.mapNotNull { m -> if (isRevise(m.body)) decode(m.body)?.let { m to it } else null }
        if (acts.isEmpty()) return Applied(log, emptyList())
        val byTarget = acts.groupBy { it.second.targetId }
        val unsent = mutableListOf<PeerMessage>()
        val origins = mutableListOf<PeerMessage>()
        val revised = log.map { line ->
            val own = byTarget[line.id]?.filter { it.first.fromId == line.fromId }
            if (own.isNullOrEmpty() || line.deletedAtMs > 0L) return@map line
            val gone = own.filter { it.second is Unsend }.minByOrNull { it.first.sentAtMs }
            if (gone != null && unsendable(line)) {
                unsent += line
                return@map tombstoned(line, gone.first.sentAtMs)
            }
            val last = own.filter { it.second is Edit && inWindow(line, it.first.sentAtMs) }
                .maxWithOrNull(compareBy({ it.first.sentAtMs }, { it.first.id }))
                ?: return@map line
            if (last.first.sentAtMs < line.editedAtMs || !(editable(line) || captionable(line))) return@map line
            if (line.editedAtMs == 0L && own.none { it.first.id == ORIGIN + line.id }) origin(line)?.let(origins::add)
            edited(line, (last.second as Edit).text, last.first.sentAtMs)
        }
        // Стертий рядок не лишає своїх слів у кадрах правки.
        val dead = revised.filter { it.deletedAtMs > 0L }.mapTo(HashSet()) { it.id }
        val traces = acts.filter { it.second is Edit && it.second.targetId in dead }.mapTo(HashSet()) { it.first.id }
        val kept = (revised + origins).let { all -> if (traces.isEmpty()) all else all.filterNot { it.id in traces } }
        if (unsent.isEmpty()) return Applied(kept, emptyList())
        val blobs = unsent.mapNotNull { PeerFile.describe(it.body)?.blobId }.toSet()
        return Applied(kept.filterNot { PeerFile.head(it.body)?.blobId in blobs }, unsent)
    }

    /**
     * Перша версія живе лише тут: [apply] переписує рядок на місці, а дротом
     * вона ніколи не їхала. Кадр — уже доставлений, тож черга його не шле.
     */
    fun origin(line: PeerMessage): PeerMessage? {
        val body = encode(Edit(line.id, words(line))) ?: return null
        return PeerMessage(ORIGIN + line.id, line.fromId, line.toId, body, line.sentAtMs, state = PeerMessage.State.DELIVERED)
    }

    data class Version(val text: String, val atMs: Long)

    /** Новіші вище; першої версії немає для правок, що лягли до 25.10. */
    fun history(log: List<PeerMessage>, line: PeerMessage): List<Version> {
        if (line.editedAtMs == 0L || line.deletedAtMs > 0L) return emptyList()
        val past = log.asSequence()
            .filter { it.fromId == line.fromId && isEdit(it.body) && it.sentAtMs < line.editedAtMs && inWindow(line, it.sentAtMs) }
            .mapNotNull { m -> (decode(m.body) as? Edit)?.takeIf { it.targetId == line.id }?.let { Version(it.text, m.sentAtMs) } }
            .sortedByDescending { it.atMs }
        return (sequenceOf(Version(words(line), line.editedAtMs)) + past).distinctBy { it.atMs }.toList()
    }

    private fun words(line: PeerMessage): String = said(line.body).let { PeerFile.describe(it)?.caption ?: it }

    /** Рядок групи — конверт, тож судиться те, що в ньому. */
    private fun spoken(line: PeerMessage): Boolean = PeerOutbox.isSpoken(line.copy(body = said(line.body)))

    private fun said(body: String): String = PeerGroup.decode(body)?.text ?: body

    private fun rewrap(body: String, text: String): String =
        if (PeerGroup.decode(body) != null) body.substringBefore('\n') + "\n" + text else text

    private fun isWords(text: String): Boolean =
        !text.trimStart().startsWith(PeerFrames.MARK) && PeerFile.describe(text) == null

    private fun isRef(value: String): Boolean =
        value.length in 1..64 &&
            value.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '_' }
}
